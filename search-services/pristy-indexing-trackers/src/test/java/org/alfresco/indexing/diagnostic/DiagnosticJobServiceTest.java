/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.diagnostic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class DiagnosticJobServiceTest
{
    private static final Instant NOW = Instant.parse("2026-09-25T15:02:11.456Z");

    private final DiagnosticStore store = mock(DiagnosticStore.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<DiagnosticSnapshot> published = new CopyOnWriteArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @After
    public void stopExecutors()
    {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    public void aJobWalksFourPhasesPerCoreThenTheRepairReport() throws Exception
    {
        DiagnosticJobService service = service(walkingEveryPhase(), Runnable::run);

        service.start("admin");

        DiagnosticSnapshot done = service.snapshot();
        assertEquals("done", done.state());
        assertEquals("admin", done.startedBy());
        assertEquals("2026-09-25T15:02:11Z", done.startedAt());
        assertEquals("2026-09-25T15:02:11Z", done.finishedAt());
        assertEquals(Integer.valueOf(9), done.steps());
        assertEquals(Integer.valueOf(9), done.step());
        assertEquals(List.of("report", "errorNodes", "partialFailures"), new ArrayList<>(done.result().keySet()));
        assertEquals(Set.of("alfresco", "archive"), ((Map<?, ?>) done.result().get("report")).keySet());
        assertEquals(Map.of("totalErrorNodes", 0), done.result().get("errorNodes"));
        assertEquals(List.of(), done.result().get("partialFailures"));
        List<Integer> steps = published.stream().map(DiagnosticSnapshot::step).toList();
        for (int i = 1; i < steps.size(); i++)
        {
            assertTrue(steps.toString(), steps.get(i) >= steps.get(i - 1));
        }
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), steps.stream().distinct().toList());
        assertTrue(published.stream().anyMatch(snapshot -> "acl.index".equals(snapshot.cores().get("archive").phase())));
        ArgumentCaptor<StoredDiagnostic> saved = ArgumentCaptor.forClass(StoredDiagnostic.class);
        verify(store).save(eq("alfresco"), saved.capture());
        assertEquals("admin", saved.getValue().startedBy());
        assertEquals("2026-09-25T15:02:11Z", saved.getValue().finishedAt());
        assertEquals(Map.of("DB transaction count", 10L), saved.getValue().report());
        verify(store).save(eq("archive"), any(StoredDiagnostic.class));
    }

    @Test
    public void aCoreWhoseReportFailsIsRecordedAndTheJobStillCompletes()
    {
        CoreReporter reporter = (core, listeners) -> {
            if ("archive".equals(core))
            {
                return Map.of("error", "Solr is down");
            }
            return Map.of("DB transaction count", 1L);
        };
        DiagnosticJobService service = service(reporter, Runnable::run);

        service.start("admin");

        DiagnosticSnapshot done = service.snapshot();
        assertEquals("done", done.state());
        assertEquals(Map.of("error", "Solr is down"), ((Map<?, ?>) done.result().get("report")).get("archive"));
    }

    @Test
    public void aRepairReportThatThrowsIsAPartialFailure()
    {
        DiagnosticJobService service = service(walkingEveryPhase(), () -> {
            throw new RuntimeException("Solr unreachable");
        }, Runnable::run);

        service.start("admin");

        DiagnosticSnapshot done = service.snapshot();
        assertEquals("done", done.state());
        assertEquals(List.of("errorNodes"), done.result().get("partialFailures"));
        assertEquals(Map.of(), done.result().get("errorNodes"));
    }

    @Test
    public void aFailedJobKeepsThePreviousResult() throws Exception
    {
        AtomicBoolean failing = new AtomicBoolean(false);
        CoreReporter reporter = (core, listeners) -> {
            if (failing.get())
            {
                throw new IllegalStateException("the trackers are gone");
            }
            return Map.of("DB transaction count", 10L);
        };
        DiagnosticJobService service = service(reporter, Runnable::run);
        service.start("admin");
        Map<String, Object> previous = service.snapshot().result();
        failing.set(true);

        service.start("other");

        DiagnosticSnapshot failed = service.snapshot();
        assertEquals("failed", failed.state());
        assertEquals("the trackers are gone", failed.error());
        assertEquals("other", failed.startedBy());
        assertSame(previous, failed.result());
        verify(store, times(2)).save(anyString(), any(StoredDiagnostic.class));
    }

    @Test
    public void anErrorFailsTheJobSoANewOneCanStart() throws Exception
    {
        AtomicBoolean failing = new AtomicBoolean(false);
        CoreReporter reporter = (core, listeners) -> {
            if (failing.get())
            {
                throw new AssertionError("boom");
            }
            return Map.of("DB transaction count", 10L);
        };
        List<Error> rethrown = new CopyOnWriteArrayList<>();
        Executor tolerant = task -> {
            try
            {
                task.run();
            }
            catch (Error e)
            {
                rethrown.add(e);
            }
        };
        DiagnosticJobService service = service(reporter, tolerant);
        service.start("admin");
        Map<String, Object> previous = service.snapshot().result();
        failing.set(true);

        service.start("other");

        DiagnosticSnapshot failed = service.snapshot();
        assertEquals("failed", failed.state());
        assertEquals("boom", failed.error());
        assertSame(previous, failed.result());
        assertEquals(1, rethrown.size());
        assertTrue(rethrown.get(0) instanceof AssertionError);
        assertEquals("boom", rethrown.get(0).getMessage());

        failing.set(false);
        service.start("third");

        assertEquals("done", service.snapshot().state());
    }

    @Test
    public void aBlankUserIsRecordedAsNobody()
    {
        DiagnosticJobService service = service(walkingEveryPhase(), Runnable::run);

        service.start("   ");

        assertNull(service.snapshot().startedBy());
    }

    @Test
    public void aSecondStartJoinsTheRunningJob() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        CoreReporter blocking = (core, listeners) -> {
            reports.incrementAndGet();
            await(release);
            return Map.of();
        };
        DiagnosticJobService service = service(blocking, singleThread());
        service.addListener(snapshot -> {
            if ("done".equals(snapshot.state()))
            {
                finished.countDown();
            }
        });

        DiagnosticSnapshot first = service.start("admin");
        DiagnosticSnapshot second = service.start("other");

        assertEquals("running", second.state());
        assertEquals("admin", second.startedBy());
        assertEquals(first.startedAt(), second.startedAt());
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals("one job, two cores", 2, reports.get());
    }

    @Test
    public void concurrentStartsScheduleASingleJob() throws Exception
    {
        List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        DiagnosticJobService service = service(walkingEveryPhase(), scheduled::add);
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        executors.add(pool);
        List<Future<DiagnosticSnapshot>> answers = new ArrayList<>();
        for (int i = 0; i < callers; i++)
        {
            String user = "user" + i;
            answers.add(pool.submit(() -> {
                go.await();
                return service.start(user);
            }));
        }

        go.countDown();
        Set<String> startedBy = new HashSet<>();
        for (Future<DiagnosticSnapshot> answer : answers)
        {
            startedBy.add(answer.get(5, TimeUnit.SECONDS).startedBy());
        }

        assertEquals(1, scheduled.size());
        assertEquals(startedBy.toString(), 1, startedBy.size());
    }

    @Test
    public void cancellingMidPhaseStopsAtTheNextBatchAndKeepsTheStoredResult() throws Exception
    {
        AtomicBoolean blockNextRun = new AtomicBoolean(false);
        AtomicInteger batchesAfterCancel = new AtomicInteger();
        CountDownLatch inPhase = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CoreReporter reporter = (core, listeners) -> {
            ProgressListener walk = listeners.apply(DiagnosticPhase.METADATA_DB);
            walk.onProgress(1L, 10L);
            if (blockNextRun.get())
            {
                inPhase.countDown();
                await(release);
                walk.onProgress(2L, 10L);
                batchesAfterCancel.incrementAndGet();
            }
            return Map.of("DB transaction count", 10L);
        };
        DiagnosticJobService service = service(reporter, singleThread());
        service.addListener(snapshot -> {
            if ("done".equals(snapshot.state()))
            {
                firstDone.countDown();
            }
            if ("cancelled".equals(snapshot.state()))
            {
                cancelled.countDown();
            }
        });
        service.start("admin");
        assertTrue(firstDone.await(5, TimeUnit.SECONDS));
        Map<String, Object> previous = service.snapshot().result();
        blockNextRun.set(true);

        service.start("other");
        assertTrue(inPhase.await(5, TimeUnit.SECONDS));
        Optional<DiagnosticSnapshot> cancelling = service.cancel();
        release.countDown();
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));

        assertTrue(cancelling.isPresent());
        assertEquals("running", cancelling.get().state());
        DiagnosticSnapshot stopped = service.snapshot();
        assertEquals("cancelled", stopped.state());
        assertNull(stopped.error());
        assertEquals("2026-09-25T15:02:11Z", stopped.finishedAt());
        assertSame(previous, stopped.result());
        assertEquals("the batch after the cancellation does not run", 0, batchesAfterCancel.get());
        verify(store, times(2)).save(anyString(), any(StoredDiagnostic.class));
    }

    @Test
    public void cancelAnswersNothingWhenNoJobRuns()
    {
        DiagnosticJobService service = service(walkingEveryPhase(), Runnable::run);

        assertTrue(service.cancel().isEmpty());
        service.start("admin");
        assertTrue(service.cancel().isEmpty());
    }

    @Test
    public void aCancelledJobWritesNoDocument() throws Exception
    {
        DiagnosticJobService[] holder = new DiagnosticJobService[1];
        CoreReporter reporter = (core, listeners) -> {
            holder[0].cancel();
            listeners.apply(DiagnosticPhase.METADATA_DB).onProgress(0L, 10L);
            return Map.of();
        };
        holder[0] = service(reporter, Runnable::run);

        holder[0].start("admin");

        assertEquals("cancelled", holder[0].snapshot().state());
        verify(store, never()).save(anyString(), any(StoredDiagnostic.class));
    }

    @Test
    public void aJobStartedAfterACancelledOneRunsToTheEnd() throws Exception
    {
        DiagnosticJobService[] holder = new DiagnosticJobService[1];
        AtomicBoolean cancelling = new AtomicBoolean(true);
        CoreReporter reporter = (core, listeners) -> {
            if (cancelling.get())
            {
                holder[0].cancel();
            }
            listeners.apply(DiagnosticPhase.METADATA_DB).onProgress(0L, 10L);
            return Map.of("DB transaction count", 10L);
        };
        holder[0] = service(reporter, Runnable::run);
        holder[0].start("admin");
        assertEquals("cancelled", holder[0].snapshot().state());
        cancelling.set(false);

        holder[0].start("other");

        DiagnosticSnapshot done = holder[0].snapshot();
        assertEquals("done", done.state());
        assertEquals("other", done.startedBy());
        verify(store).save(eq("alfresco"), any(StoredDiagnostic.class));
        verify(store).save(eq("archive"), any(StoredDiagnostic.class));
    }

    @Test
    public void theStoredResultIsReadBackOnStartup() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        when(store.load("archive")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 3))));
        DiagnosticJobService service = restorableService(walkingEveryPhase(), new ImmediateScheduledExecutor());

        service.onApplicationReady();
        DiagnosticSnapshot restored = service.snapshot();

        assertEquals("done", restored.state());
        assertEquals("admin", restored.startedBy());
        assertEquals("2026-09-25T15:02:11Z", restored.startedAt());
        assertEquals("2026-09-25T15:10:00Z", restored.finishedAt());
        assertEquals(Integer.valueOf(9), restored.steps());
        Map<?, ?> report = (Map<?, ?>) restored.result().get("report");
        assertEquals(Map.of("DB transaction count", 12), report.get("alfresco"));
        assertEquals(Map.of("DB transaction count", 3), report.get("archive"));
        assertEquals(Map.of("totalErrorNodes", 0), restored.result().get("errorNodes"));
        verify(store, times(1)).load("alfresco");
    }

    @Test
    public void onlyTheLatestRunIsRestored() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        when(store.load("archive")).thenReturn(Optional.of(stored("2026-09-24T09:00:00Z", Map.of("DB transaction count", 3))));
        DiagnosticJobService service = new DiagnosticJobService(() -> List.of("alfresco", "archive", "added"),
                walkingEveryPhase(), () -> Map.of(), store, clock, Runnable::run, new ImmediateScheduledExecutor(),
                DiagnosticJobService.DEFAULT_RESTORE_RETRY_DELAY_MILLIS);

        service.onApplicationReady();
        DiagnosticSnapshot restored = service.snapshot();

        assertEquals(Set.of("alfresco"), restored.cores().keySet());
        assertEquals(Set.of("alfresco"), ((Map<?, ?>) restored.result().get("report")).keySet());
        assertEquals(Integer.valueOf(5), restored.steps());
    }

    @Test
    public void nothingStoredStaysIdle()
    {
        DiagnosticJobService service = service(walkingEveryPhase(), Runnable::run);

        service.onApplicationReady();

        assertEquals("idle", service.snapshot().state());
        assertNull(service.snapshot().result());
    }

    @Test
    public void snapshotDoesNotWaitForARestoreInProgress() throws Exception
    {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.load("alfresco")).thenAnswer(invocation -> {
            loadStarted.countDown();
            await(release);
            return Optional.empty();
        });
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        executors.add(scheduler);
        DiagnosticJobService service = restorableService(walkingEveryPhase(), scheduler);

        service.onApplicationReady();
        assertTrue(loadStarted.await(5, TimeUnit.SECONDS));

        assertEquals("idle", service.snapshot().state());

        release.countDown();
    }

    @Test
    public void theRestoredSnapshotIsPublished() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        DiagnosticJobService service = restorableService(walkingEveryPhase(), new ImmediateScheduledExecutor());

        service.onApplicationReady();

        assertEquals(1, published.size());
        assertEquals("done", published.get(0).state());
    }

    @Test
    public void whileAJobRunsTheStoredResultStaysVisible() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        when(store.load("archive")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 3))));
        DiagnosticJobService service = restorableService(walkingEveryPhase(), new ImmediateScheduledExecutor());

        service.onApplicationReady();
        service.start("other");

        assertEquals("done", published.get(0).state());
        DiagnosticSnapshot running = published.get(1);
        assertEquals("running", running.state());
        assertEquals(Map.of("DB transaction count", 12),
                ((Map<?, ?>) running.result().get("report")).get("alfresco"));
    }

    @Test
    public void aRestoreLandingAfterStartKeepsRunningAndFillsLastResult() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CoreReporter blocking = (core, listeners) -> {
            await(release);
            throw new IllegalStateException("stop after restore");
        };
        DiagnosticJobService service = new DiagnosticJobService(() -> List.of("alfresco", "archive"), blocking,
                () -> Map.of(), store, clock, singleThread(), new ImmediateScheduledExecutor(),
                DiagnosticJobService.DEFAULT_RESTORE_RETRY_DELAY_MILLIS);
        service.addListener(snapshot -> {
            published.add(snapshot);
            if ("failed".equals(snapshot.state()))
            {
                failed.countDown();
            }
        });

        DiagnosticSnapshot running = service.start("admin");
        assertEquals("running", running.state());
        assertNull(running.result());

        service.onApplicationReady();
        DiagnosticSnapshot stillRunning = service.snapshot();
        assertEquals("running", stillRunning.state());
        assertEquals(Map.of("DB transaction count", 12),
                ((Map<?, ?>) stillRunning.result().get("report")).get("alfresco"));
        assertEquals(stillRunning, published.get(published.size() - 1));

        release.countDown();
        assertTrue(failed.await(5, TimeUnit.SECONDS));

        DiagnosticSnapshot failedSnapshot = service.snapshot();
        assertEquals("failed", failedSnapshot.state());
        assertEquals(Map.of("DB transaction count", 12),
                ((Map<?, ?>) failedSnapshot.result().get("report")).get("alfresco"));
    }

    @Test
    public void aRestoreLandingAfterAFailedJobFillsItsResult() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        CoreReporter failing = (core, listeners) -> {
            throw new IllegalStateException("the trackers are gone");
        };
        DiagnosticJobService service = restorableService(failing, new ImmediateScheduledExecutor());
        service.start("admin");
        assertEquals("failed", service.snapshot().state());
        assertNull(service.snapshot().result());

        service.onApplicationReady();

        DiagnosticSnapshot failed = service.snapshot();
        assertEquals("failed", failed.state());
        assertEquals("the trackers are gone", failed.error());
        assertEquals(Map.of("DB transaction count", 12),
                ((Map<?, ?>) failed.result().get("report")).get("alfresco"));
        assertEquals(failed, published.get(published.size() - 1));
    }

    @Test
    public void anUnexpectedRestoreFailureIsRetried() throws Exception
    {
        when(store.load("alfresco")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        AtomicInteger calls = new AtomicInteger();
        Supplier<List<String>> cores = () -> {
            if (calls.incrementAndGet() == 1)
            {
                throw new IllegalStateException("registry not ready");
            }
            return List.of("alfresco");
        };
        DiagnosticJobService service = new DiagnosticJobService(cores, walkingEveryPhase(), () -> Map.of(), store,
                clock, Runnable::run, new ImmediateScheduledExecutor(),
                DiagnosticJobService.DEFAULT_RESTORE_RETRY_DELAY_MILLIS);

        service.onApplicationReady();

        assertEquals("done", service.snapshot().state());
        assertEquals(2, calls.get());
    }

    @Test
    public void aCoreThatFailsToLoadIsSkippedAndOthersRestore() throws Exception
    {
        when(store.load("alfresco")).thenThrow(new RuntimeException("core not loaded"));
        when(store.load("archive")).thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 3))));
        DiagnosticJobService service = restorableService(walkingEveryPhase(), new ImmediateScheduledExecutor());

        service.onApplicationReady();
        DiagnosticSnapshot restored = service.snapshot();

        assertEquals("done", restored.state());
        assertEquals(Set.of("archive"), restored.cores().keySet());
    }

    @Test
    public void aFailingAttemptIsRetriedByTheSchedulerAndEventuallyRestores() throws Exception
    {
        when(store.load("alfresco"))
                .thenThrow(new IOException("Solr is starting"))
                .thenReturn(Optional.of(stored("2026-09-25T15:10:00Z", Map.of("DB transaction count", 12))));
        DiagnosticJobService service = restorableService(walkingEveryPhase(), new ImmediateScheduledExecutor());

        service.onApplicationReady();

        assertEquals("done", service.snapshot().state());
        verify(store, times(2)).load("alfresco");
    }

    private DiagnosticJobService restorableService(CoreReporter reporter, ScheduledExecutorService scheduler)
    {
        DiagnosticJobService service = new DiagnosticJobService(() -> List.of("alfresco", "archive"), reporter,
                () -> Map.of("totalErrorNodes", 0), store, clock, Runnable::run, scheduler,
                DiagnosticJobService.DEFAULT_RESTORE_RETRY_DELAY_MILLIS);
        service.addListener(published::add);
        return service;
    }

    private static final class ImmediateScheduledExecutor extends AbstractExecutorService
            implements ScheduledExecutorService
    {
        private boolean stopped;

        @Override
        public void execute(Runnable command)
        {
            command.run();
        }

        @Override
        public void shutdown()
        {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            stopped = true;
            return List.of();
        }

        @Override
        public boolean isShutdown()
        {
            return stopped;
        }

        @Override
        public boolean isTerminated()
        {
            return stopped;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
        {
            return true;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
        {
            command.run();
            return null;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                TimeUnit unit)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static StoredDiagnostic stored(String finishedAt, Map<String, Object> section)
    {
        return new StoredDiagnostic("2026-09-25T15:02:11Z", "admin", finishedAt, section,
                Map.of("totalErrorNodes", 0), List.of());
    }

    private DiagnosticJobService service(CoreReporter reporter, Executor executor)
    {
        return service(reporter, () -> Map.of("totalErrorNodes", 0), executor);
    }

    private DiagnosticJobService service(CoreReporter reporter, Supplier<Map<String, Object>> repairReport,
            Executor executor)
    {
        DiagnosticJobService service = new DiagnosticJobService(() -> List.of("alfresco", "archive"), reporter,
                repairReport, store, clock, executor);
        service.addListener(published::add);
        return service;
    }

    private ExecutorService singleThread()
    {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private static CoreReporter walkingEveryPhase()
    {
        return (core, listeners) -> {
            for (DiagnosticPhase phase : DiagnosticPhase.values())
            {
                listeners.apply(phase).onProgress(0L, 10L);
                listeners.apply(phase).onProgress(10L, 10L);
            }
            return Map.of("DB transaction count", 10L);
        };
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            if (!latch.await(5, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("latch timed out");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
