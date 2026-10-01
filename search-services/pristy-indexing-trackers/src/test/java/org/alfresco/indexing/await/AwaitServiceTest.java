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

package org.alfresco.indexing.await;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ERROR_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.alfresco.indexing.tracker.CommitListener;
import org.junit.Test;

public class AwaitServiceTest
{
    private static final String CORE = "alfresco";
    private static final long LAG = 1000L;
    private static final long TIMEOUT = 20000L;

    private final FakeTrackers trackers = new FakeTrackers();
    private final FakeDatabase database = new FakeDatabase();
    private final FakeIndex index = new FakeIndex();
    private final ManualDelays delays = new ManualDelays();
    private final ManualExecutor repository = new ManualExecutor();
    private final ManualExecutor worker = new ManualExecutor();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private AwaitService service = service(CORE, 10000);

    @Test
    public void aNodeAlreadySearchableIsReleasedAtOnce()
    {
        database.live(1L, 10L);
        index.node(1L, 10L);
        RecordingSink sink = new RecordingSink();

        service.open(Set.of(1L), TIMEOUT, sink);

        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), sink.events);
        assertEquals(List.of(), trackers.metadataTriggers);
        assertTrue(delays.single(TIMEOUT).cancelled);
        assertEquals(0, service.pendingWaiters());
    }

    @Test
    public void aNodeIndexedAfterTheTriggerIsReleasedByTheNextCommit()
    {
        database.live(1L, 10L);
        RecordingSink sink = new RecordingSink();

        service.open(Set.of(1L), TIMEOUT, sink);

        assertEquals(List.of(), sink.events);
        assertEquals(List.of(LAG), trackers.metadataTriggers);
        trackers.started.run();
        trackers.ended.run();
        assertEquals(1, trackers.commitTriggers);
        index.node(1L, 10L);
        trackers.commitListener.afterCommit(CORE);
        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), sink.events);
    }

    @Test
    public void theTimeoutEndsTheStreamWithThePendingNodes()
    {
        database.live(1L, 10L);
        database.live(2L, 10L);
        index.node(1L, 10L);
        RecordingSink sink = new RecordingSink();

        service.open(new LinkedHashSet<>(List.of(1L, 2L)), TIMEOUT, sink);
        delays.single(TIMEOUT).run();

        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of(2L))), sink.events);
        assertEquals(0, service.pendingWaiters());
    }

    @Test
    public void anErrorNodeIsReportedAsAnError()
    {
        database.live(1L, 10L);
        index.error(1L, 10L);
        RecordingSink sink = new RecordingSink();

        service.open(Set.of(1L), TIMEOUT, sink);

        assertEquals(List.of(new AwaitEvent.Failed(1L, "ERROR"), new AwaitEvent.End(List.of())), sink.events);
    }

    @Test
    public void aNodeTheRepositoryDoesNotHoldIsAnOrphan()
    {
        database.orphan(1L);
        RecordingSink sink = new RecordingSink();

        service.open(Set.of(1L), TIMEOUT, sink);

        assertEquals(List.of(new AwaitEvent.Failed(1L, "ORPHAN"), new AwaitEvent.End(List.of())), sink.events);
        assertEquals(List.of(), index.queries);
        assertEquals(List.of(), trackers.metadataTriggers);
    }

    @Test
    public void anUnreadableRepositoryIsReportedPerNode()
    {
        database.unreachable(1L);
        RecordingSink sink = new RecordingSink();

        service.open(new LinkedHashSet<>(List.of(1L, 2L)), TIMEOUT, sink);

        assertEquals(List.of(new AwaitEvent.Failed(1L, "UNREACHABLE"), new AwaitEvent.Failed(2L, "UNREACHABLE"),
                new AwaitEvent.End(List.of())), sink.events);
    }

    @Test
    public void manyWaitersAreReleasedByOneCommit()
    {
        List<RecordingSink> sinks = new ArrayList<>();
        for (int request = 0; request < 50; request++)
        {
            Set<Long> dbids = new LinkedHashSet<>();
            for (long offset = 1; offset <= 3; offset++)
            {
                long dbid = request * 3L + offset;
                database.live(dbid, 10L);
                dbids.add(dbid);
            }
            RecordingSink sink = new RecordingSink();
            sinks.add(sink);
            service.open(dbids, TIMEOUT, sink);
        }

        assertEquals(List.of(LAG), trackers.metadataTriggers);
        trackers.started.run();
        trackers.ended.run();
        assertEquals(1, trackers.commitTriggers);
        for (long dbid = 1; dbid <= 150; dbid++)
        {
            index.node(dbid, 10L);
        }
        int queriesBefore = index.queries.size();
        trackers.commitListener.afterCommit(CORE);

        assertEquals(queriesBefore + 1, index.queries.size());
        assertEquals(150, index.queries.get(index.queries.size() - 1).size());
        for (RecordingSink sink : sinks)
        {
            assertEquals(4, sink.events.size());
            assertEquals(new AwaitEvent.End(List.of()), sink.events.get(3));
        }
        assertEquals(0, service.pendingWaiters());
    }

    @Test
    public void aRequestAfterTheTriggeredRunStartedTriggersAgain()
    {
        database.live(1L, 10L);
        database.live(2L, 10L);

        service.open(Set.of(1L), TIMEOUT, new RecordingSink());
        trackers.started.run();
        service.open(Set.of(2L), TIMEOUT, new RecordingSink());

        assertEquals(List.of(LAG, LAG), trackers.metadataTriggers);
    }

    @Test
    public void aTriggerTheSchedulerRefusedIsRetriedByTheNextRequest()
    {
        trackers.accepting = false;
        database.live(1L, 10L);
        database.live(2L, 10L);

        service.open(Set.of(1L), TIMEOUT, new RecordingSink());
        service.open(Set.of(2L), TIMEOUT, new RecordingSink());

        assertEquals(List.of(LAG, LAG), trackers.metadataTriggers);
    }

    @Test
    public void aStaleTriggerIsReplaced()
    {
        database.live(1L, 10L);
        database.live(2L, 10L);

        service.open(Set.of(1L), TIMEOUT, new RecordingSink());
        clock.addAndGet(LAG + AwaitService.STALE_TRIGGER_MILLIS);
        service.open(Set.of(2L), TIMEOUT, new RecordingSink());

        assertEquals(List.of(LAG, LAG), trackers.metadataTriggers);
    }

    @Test
    public void aClientThatDisconnectsFreesItsWaiters()
    {
        database.live(1L, 10L);
        database.live(2L, 10L);
        RecordingSink sink = new RecordingSink();
        AwaitHandle handle = service.open(new LinkedHashSet<>(List.of(1L, 2L)), TIMEOUT, sink);

        service.cancel(handle);

        assertEquals(0, service.pendingWaiters());
        assertTrue(delays.single(TIMEOUT).cancelled);
        index.node(1L, 10L);
        trackers.commitListener.afterCommit(CORE);
        trackers.ended.run();
        assertEquals(List.of(), sink.events);
        assertEquals(0, trackers.commitTriggers);
    }

    @Test
    public void theCapacityIsSharedAcrossRequests()
    {
        service = service(CORE, 5);
        for (long dbid = 1; dbid <= 6; dbid++)
        {
            database.live(dbid, 10L);
        }
        service.open(Set.of(1L, 2L, 3L), TIMEOUT, new RecordingSink());

        assertThrows(AwaitCapacityException.class, () -> service.open(Set.of(4L, 5L, 6L), TIMEOUT, new RecordingSink()));

        delays.single(TIMEOUT).run();
        service.open(Set.of(4L, 5L, 6L), TIMEOUT, new RecordingSink());
        assertEquals(3, service.pendingWaiters());
    }

    @Test
    public void aNodeNotYetVisibleAfterACommitIsCheckedOnceMore()
    {
        database.live(1L, 10L);
        RecordingSink sink = new RecordingSink();
        service.open(Set.of(1L), TIMEOUT, sink);

        trackers.commitListener.afterCommit(CORE);
        trackers.commitListener.afterCommit(CORE);

        assertEquals(1, delays.scheduledWith(AwaitService.RECHECK_DELAY_MILLIS).size());
        index.node(1L, 10L);
        delays.single(AwaitService.RECHECK_DELAY_MILLIS).run();
        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), sink.events);
    }

    @Test
    public void commitsWhileTheWorkerIsBlockedEnqueueOneCheck()
    {
        database.live(1L, 10L);
        RecordingSink sink = new RecordingSink();
        service.open(Set.of(1L), TIMEOUT, sink);
        worker.holding = true;

        trackers.commitListener.afterCommit(CORE);
        trackers.commitListener.afterCommit(CORE);
        trackers.commitListener.afterCommit(CORE);

        assertEquals(1, worker.queued.size());
        index.node(1L, 10L);
        worker.release();
        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), sink.events);
        worker.holding = true;
        trackers.commitListener.afterCommit(CORE);
        assertEquals(1, worker.queued.size());
    }

    @Test
    public void aFailingIndexQueryLeavesTheNodesPending()
    {
        database.live(1L, 10L);
        index.failing = true;
        RecordingSink sink = new RecordingSink();

        service.open(Set.of(1L), TIMEOUT, sink);

        assertEquals(List.of(), sink.events);
        index.failing = false;
        index.node(1L, 10L);
        trackers.commitListener.afterCommit(CORE);
        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), sink.events);
    }

    @Test
    public void withoutAWorkspaceCoreNothingIsAccepted()
    {
        service = service(null, 10);

        assertThrows(AwaitUnavailableException.class, () -> service.open(Set.of(1L), TIMEOUT, new RecordingSink()));
        assertEquals(0, service.pendingWaiters());
    }

    @Test
    public void aCommitOnAnotherCoreChecksNothing()
    {
        database.live(1L, 10L);
        service.open(Set.of(1L), TIMEOUT, new RecordingSink());
        int queriesBefore = index.queries.size();

        trackers.commitListener.afterCommit("archive");

        assertEquals(queriesBefore, index.queries.size());
    }

    @Test
    public void theTrackersAreHookedOnce()
    {
        trackers.hookable = false;
        database.live(1L, 10L);
        database.live(2L, 10L);
        database.live(3L, 10L);

        service.open(Set.of(1L), TIMEOUT, new RecordingSink());
        trackers.hookable = true;
        service.open(Set.of(2L), TIMEOUT, new RecordingSink());
        service.open(Set.of(3L), TIMEOUT, new RecordingSink());

        assertEquals(2, trackers.hooks.get());
    }

    @Test
    public void theTrackersAreHookedOnceUnderConcurrentFirstRequests() throws InterruptedException
    {
        trackers.firstHookEntered = new CountDownLatch(1);
        trackers.firstHookRelease = new CountDownLatch(1);
        database.live(1L, 10L);
        database.live(2L, 10L);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try
            {
                service.open(Set.of(1L), TIMEOUT, new RecordingSink());
            }
            catch (Throwable e)
            {
                failure.set(e);
            }
        });

        first.start();
        assertTrue(trackers.firstHookEntered.await(10, TimeUnit.SECONDS));
        service.open(Set.of(2L), TIMEOUT, new RecordingSink());
        trackers.firstHookRelease.countDown();
        first.join(10000L);

        assertFalse(first.isAlive());
        assertNull(failure.get());
        assertEquals(1, trackers.hooks.get());
    }

    @Test
    public void aBlockedRepositoryReadDoesNotDelayAPostCommitRelease()
    {
        database.live(1L, 10L);
        database.live(2L, 10L);
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        service.open(Set.of(1L), TIMEOUT, first);
        repository.holding = true;
        service.open(Set.of(2L), TIMEOUT, second);

        index.node(1L, 10L);
        trackers.commitListener.afterCommit(CORE);

        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), first.events);
        assertEquals(List.of(), second.events);
        assertEquals(1, repository.queued.size());
        index.node(2L, 10L);
        repository.release();
        assertEquals(List.of(new AwaitEvent.Searchable(2L), new AwaitEvent.End(List.of())), second.events);
    }

    @Test
    public void anErrorWhileResolvingStillEndsTheStreamAtTheTimeout()
    {
        database.error = new LinkageError("repository client bug");
        RecordingSink sink = new RecordingSink();

        assertThrows(LinkageError.class, () -> service.open(Set.of(1L), TIMEOUT, sink));

        assertEquals(1, service.pendingWaiters());
        delays.single(TIMEOUT).run();
        assertEquals(List.of(new AwaitEvent.End(List.of(1L))), sink.events);
        assertEquals(0, service.pendingWaiters());
    }

    private AwaitService service(String core, int maxWaiters)
    {
        return new AwaitService(new AwaitService.Settings(core, LAG, maxWaiters), trackers, database, index,
                repository, worker, Runnable::run, delays, clock::get);
    }

    private static final class FakeTrackers implements CoreTrackers
    {
        final List<Long> metadataTriggers = new ArrayList<>();
        int commitTriggers;
        final AtomicInteger hooks = new AtomicInteger();
        boolean accepting = true;
        boolean hookable = true;
        CountDownLatch firstHookEntered;
        CountDownLatch firstHookRelease;
        Runnable started = () -> { };
        Runnable ended = () -> { };
        CommitListener commitListener = core -> { };

        @Override
        public boolean hook(String core, Runnable metadataRunStarted, Runnable metadataRunEnded,
                CommitListener listener)
        {
            int call = hooks.incrementAndGet();
            if (call == 1 && firstHookRelease != null)
            {
                firstHookEntered.countDown();
                try
                {
                    firstHookRelease.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
            if (!hookable)
            {
                return false;
            }
            started = metadataRunStarted;
            ended = metadataRunEnded;
            commitListener = listener;
            return true;
        }

        @Override
        public boolean triggerMetadata(String core, long delayMillis)
        {
            metadataTriggers.add(delayMillis);
            return accepting;
        }

        @Override
        public boolean triggerCommit(String core)
        {
            commitTriggers++;
            return accepting;
        }
    }

    private static final class FakeDatabase implements DatabaseReader
    {
        final Map<Long, DatabaseNode> nodes = new HashMap<>();
        Error error;

        void live(long dbid, long tx)
        {
            nodes.put(dbid, new DatabaseNode(dbid, DatabaseNode.Status.LIVE, tx));
        }

        void orphan(long dbid)
        {
            nodes.put(dbid, new DatabaseNode(dbid, DatabaseNode.Status.ORPHAN, -1L));
        }

        void unreachable(long dbid)
        {
            nodes.put(dbid, new DatabaseNode(dbid, DatabaseNode.Status.UNREACHABLE, -1L));
        }

        @Override
        public Map<Long, DatabaseNode> read(Collection<Long> dbids)
        {
            if (error != null)
            {
                throw error;
            }
            Map<Long, DatabaseNode> read = new HashMap<>();
            for (Long dbid : dbids)
            {
                if (nodes.containsKey(dbid))
                {
                    read.put(dbid, nodes.get(dbid));
                }
            }
            return read;
        }
    }

    private static final class FakeIndex implements IndexProbe
    {
        final Map<Long, List<IndexedDocument>> documents = new HashMap<>();
        final List<Set<Long>> queries = new ArrayList<>();
        boolean failing;

        void node(long dbid, long tx)
        {
            documents.put(dbid, List.of(new IndexedDocument(dbid, DOC_TYPE_NODE, tx)));
        }

        void error(long dbid, long tx)
        {
            documents.put(dbid, List.of(new IndexedDocument(dbid, DOC_TYPE_ERROR_NODE, tx)));
        }

        @Override
        public Map<Long, List<IndexedDocument>> find(String core, Collection<Long> dbids) throws IOException
        {
            queries.add(Set.copyOf(dbids));
            if (failing)
            {
                throw new IOException("Solr unreachable");
            }
            Map<Long, List<IndexedDocument>> found = new HashMap<>();
            for (Long dbid : dbids)
            {
                if (documents.containsKey(dbid))
                {
                    found.put(dbid, documents.get(dbid));
                }
            }
            return found;
        }
    }

    private static final class ManualExecutor implements Executor
    {
        final List<Runnable> queued = new ArrayList<>();
        boolean holding;

        @Override
        public void execute(Runnable task)
        {
            if (holding)
            {
                queued.add(task);
            }
            else
            {
                task.run();
            }
        }

        void release()
        {
            holding = false;
            List<Runnable> tasks = new ArrayList<>(queued);
            queued.clear();
            tasks.forEach(Runnable::run);
        }
    }

    private static final class ManualDelays implements Delays
    {
        final List<Scheduled> scheduled = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable task, long delayMillis)
        {
            Scheduled entry = new Scheduled(task, delayMillis);
            scheduled.add(entry);
            return () -> entry.cancelled = true;
        }

        List<Scheduled> scheduledWith(long delayMillis)
        {
            return scheduled.stream().filter(entry -> entry.delayMillis == delayMillis).toList();
        }

        Scheduled single(long delayMillis)
        {
            List<Scheduled> found = scheduledWith(delayMillis);
            assertEquals(1, found.size());
            return found.get(0);
        }
    }

    private static final class Scheduled
    {
        final Runnable task;
        final long delayMillis;
        boolean cancelled;

        Scheduled(Runnable task, long delayMillis)
        {
            this.task = task;
            this.delayMillis = delayMillis;
        }

        void run()
        {
            task.run();
        }
    }

    private static final class RecordingSink implements AwaitSink
    {
        final List<AwaitEvent> events = new ArrayList<>();

        @Override
        public void accept(AwaitEvent event)
        {
            events.add(event);
        }
    }
}
