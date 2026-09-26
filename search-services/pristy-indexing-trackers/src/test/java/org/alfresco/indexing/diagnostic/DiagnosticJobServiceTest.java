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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        Executor tolerant = task -> {
            try
            {
                task.run();
            }
            catch (Error e)
            {
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
