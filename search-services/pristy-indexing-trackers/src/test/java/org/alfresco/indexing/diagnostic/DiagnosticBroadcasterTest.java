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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class DiagnosticBroadcasterTest
{
    private final DiagnosticSnapshot idle = DiagnosticSnapshot.idle();
    private final AtomicReference<DiagnosticSnapshot> current = new AtomicReference<>(idle);
    private final ManualSender sender = new ManualSender();
    private final DiagnosticBroadcaster broadcaster = new DiagnosticBroadcaster(current::get, sender, 500L, 30000L);
    private final List<DiagnosticBroadcaster> started = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @After
    public void stop()
    {
        started.forEach(DiagnosticBroadcaster::shutdown);
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    public void handsANewSubscriberTheCurrentSnapshotAsItsFirstEvent()
    {
        RecordingEmitter emitter = new RecordingEmitter();

        broadcaster.register(emitter);
        sender.runPending();

        assertEquals(List.of(idle), emitter.snapshots);
        assertTrue(emitter.texts.toString(), emitter.texts.stream().anyMatch(text -> text.startsWith("event:state")));
    }

    @Test
    public void schedulesTheFlushAndTheKeepaliveOnItsOwnSender()
    {
        assertEquals(List.of(500L, 30000L), sender.periods);
    }

    @Test
    public void throttlesRunningSnapshotsToTheLatestOnePerFlush()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        sender.runPending();
        DiagnosticSnapshot latest = null;
        for (long batch = 1; batch <= 5; batch++)
        {
            latest = running(batch);
            trigger(latest);
        }
        sender.runPending();

        assertEquals(1, emitter.snapshots.size());
        broadcaster.flush();
        assertEquals(2, emitter.snapshots.size());
        assertSame(latest, emitter.snapshots.get(1));
        broadcaster.flush();
        assertEquals(2, emitter.snapshots.size());
    }

    @Test
    public void sendsAFinishedSnapshotAtOnceAndDropsTheQueuedOne()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        sender.runPending();
        DiagnosticSnapshot done = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin", "2026-09-25T15:04:40Z",
                List.of("alfresco"), null);

        trigger(running(1));
        trigger(done);
        sender.runPending();
        broadcaster.flush();

        assertEquals(List.of(idle, done), emitter.snapshots);
    }

    @Test
    public void sendsWhatTheSupplierHoldsWhenTheEventGoesOut()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        sender.runPending();
        DiagnosticSnapshot runningNow = running(3);
        current.set(runningNow);

        broadcaster.trigger();
        sender.runPending();
        broadcaster.flush();

        assertEquals(List.of(idle, runningNow), emitter.snapshots);
    }

    @Test
    public void aTriggerRacingTheFirstEventIsSentAfterIt() throws InterruptedException
    {
        DiagnosticSnapshot runningNow = running(1);
        DiagnosticSnapshot doneLater = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin",
                "2026-09-25T15:04:40Z", List.of("alfresco"), null);
        AtomicReference<DiagnosticSnapshot> racyCurrent = new AtomicReference<>(runningNow);
        AtomicBoolean triggered = new AtomicBoolean(false);
        AtomicReference<DiagnosticBroadcaster> holder = new AtomicReference<>();
        CountDownLatch racerFinished = new CountDownLatch(1);
        ManualSender racySender = new ManualSender();
        DiagnosticBroadcaster racy = new DiagnosticBroadcaster(() -> {
            DiagnosticSnapshot snapshot = racyCurrent.get();
            if (triggered.compareAndSet(false, true))
            {
                Thread racer = new Thread(() -> {
                    racyCurrent.set(doneLater);
                    holder.get().trigger();
                    racerFinished.countDown();
                });
                racer.setDaemon(true);
                racer.start();
                await(racerFinished);
            }
            return snapshot;
        }, racySender, 500L, 30000L);
        holder.set(racy);
        RecordingEmitter emitter = new RecordingEmitter();

        racy.register(emitter);
        racySender.runPending();

        assertEquals(List.of(runningNow, doneLater), emitter.snapshots);
    }

    @Test
    public void triggerReturnsWhileASendIsBlocked() throws Exception
    {
        DiagnosticBroadcaster real = startedBroadcaster();
        BlockingEmitter stalled = new BlockingEmitter();
        real.register(stalled);
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS));
        current.set(DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin", "2026-09-25T15:04:40Z",
                List.of("alfresco"), null));
        ExecutorService jobThread = Executors.newSingleThreadExecutor();
        executors.add(jobThread);
        try
        {
            Future<?> triggered = jobThread.submit(real::trigger);

            triggered.get(1, TimeUnit.SECONDS);
        }
        finally
        {
            stalled.release.countDown();
        }
    }

    @Test
    public void aStalledConnectionDelaysTheOthersOnlyUntilItIsEvicted() throws Exception
    {
        DiagnosticBroadcaster real = startedBroadcaster();
        BlockingEmitter stalled = new BlockingEmitter();
        stalled.failOnRelease = true;
        real.register(stalled);
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS));
        LatchedEmitter healthy = new LatchedEmitter();
        real.register(healthy);
        DiagnosticSnapshot done = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin",
                "2026-09-25T15:04:40Z", List.of("alfresco"), null);
        current.set(done);

        real.trigger();
        stalled.release.countDown();

        assertTrue(healthy.received.await(5, TimeUnit.SECONDS));
        assertEquals(done, healthy.snapshots.get(healthy.snapshots.size() - 1));
        assertEquals(1, real.subscriberCount());
    }

    @Test
    public void sendsAKeepaliveComment()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);

        broadcaster.keepalive();

        assertTrue(emitter.texts.toString(), emitter.texts.contains(":keepalive\n\n"));
    }

    @Test
    public void forgetsAConnectionThatBreaksMidStream()
    {
        RecordingEmitter healthy = new RecordingEmitter();
        RecordingEmitter broken = new RecordingEmitter();
        broadcaster.register(healthy);
        broadcaster.register(broken);
        sender.runPending();
        broken.failing = true;

        trigger(DiagnosticSnapshot.idle().ended(DiagnosticSnapshot.CANCELLED, "2026-09-25T15:03:00Z", null, null));
        sender.runPending();
        broadcaster.keepalive();

        assertEquals(1, broadcaster.subscriberCount());
        assertEquals(2, healthy.snapshots.size());
        assertTrue(healthy.texts.contains(":keepalive\n\n"));
    }

    @Test
    public void aKeepaliveOnABrokenConnectionForgetsIt()
    {
        RecordingEmitter broken = new RecordingEmitter();
        broadcaster.register(broken);
        sender.runPending();
        broken.failing = true;

        broadcaster.keepalive();

        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    public void aTriggerAfterShutdownIsIgnored()
    {
        DiagnosticBroadcaster real = startedBroadcaster();
        real.shutdown();

        real.trigger();
        real.register(new RecordingEmitter());

        assertEquals(1, real.subscriberCount());
    }

    private DiagnosticBroadcaster startedBroadcaster()
    {
        DiagnosticBroadcaster real = new DiagnosticBroadcaster(current::get, 500L, 30000L);
        started.add(real);
        return real;
    }

    private void trigger(DiagnosticSnapshot snapshot)
    {
        current.set(snapshot);
        broadcaster.trigger();
    }

    private static DiagnosticSnapshot running(long batch)
    {
        return DiagnosticSnapshot.running("2026-09-25T15:02:11Z", "admin", List.of("alfresco"), null)
                .withCore("alfresco", new DiagnosticSnapshot.CoreProgress("metadata.db", batch, 10L), 1);
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

    private static List<DiagnosticSnapshot> snapshotsOf(SseEmitter.SseEventBuilder builder)
    {
        List<DiagnosticSnapshot> found = new ArrayList<>();
        builder.build().forEach(part -> {
            if (part.getData() instanceof DiagnosticSnapshot snapshot)
            {
                found.add(snapshot);
            }
        });
        return found;
    }

    private static class RecordingEmitter extends SseEmitter
    {
        final List<DiagnosticSnapshot> snapshots = new CopyOnWriteArrayList<>();
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private volatile boolean failing;

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            if (failing)
            {
                throw new IOException("broken pipe");
            }
            builder.build().forEach(part -> {
                if (part.getData() instanceof DiagnosticSnapshot snapshot)
                {
                    snapshots.add(snapshot);
                }
                else
                {
                    texts.add(String.valueOf(part.getData()));
                }
            });
        }
    }

    private static final class LatchedEmitter extends RecordingEmitter
    {
        private final CountDownLatch received = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            boolean terminal = snapshotsOf(builder).stream()
                    .anyMatch(snapshot -> DiagnosticSnapshot.DONE.equals(snapshot.state()));
            super.send(builder);
            if (terminal)
            {
                received.countDown();
            }
        }
    }

    private static final class BlockingEmitter extends SseEmitter
    {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean failOnRelease;

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            entered.countDown();
            await(release);
            if (failOnRelease)
            {
                throw new IOException("connection reset");
            }
        }
    }

    private static final class ManualSender extends AbstractExecutorService implements ScheduledExecutorService
    {
        private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private final List<Long> periods = new ArrayList<>();
        private boolean stopped;

        void runPending()
        {
            Runnable task;
            while ((task = queue.poll()) != null)
            {
                task.run();
            }
        }

        @Override
        public void execute(Runnable command)
        {
            queue.add(command);
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
            throw new UnsupportedOperationException();
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
            periods.add(unit.toMillis(delay));
            return null;
        }
    }
}
