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

package org.alfresco.indexing.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.alfresco.solr.TrackerState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quartz.JobKey;

public class TrackerSchedulerTriggerTest
{
    private static final String TRACKER = "RecordingTracker";
    private static final String CORE = "alfresco";
    private static final String FAR_FUTURE = "0 0 0 1 1 ? 2099";

    private TrackerScheduler trackerScheduler;
    private RecordingTracker tracker;

    @Before
    public void setUp()
    {
        trackerScheduler = new TrackerScheduler("index-await-" + UUID.randomUUID());
        tracker = new RecordingTracker();
        Properties properties = new Properties();
        properties.setProperty("alfresco.metadata.tracker.cron", FAR_FUTURE);
        trackerScheduler.schedule(tracker, CORE, properties);
    }

    @After
    public void tearDown() throws Exception
    {
        tracker.release.countDown();
        trackerScheduler.shutdown();
    }

    @Test
    public void aTriggerRunsTheJobOnceAfterItsDelay() throws Exception
    {
        long before = System.nanoTime();

        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 300L));

        long started = awaitRun();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(started - before) >= 250L);
        assertNull(tracker.runStarts.poll(500L, TimeUnit.MILLISECONDS));
        assertEquals(1, tracker.runs.get());
    }

    @Test
    public void aTriggerForAnUnscheduledJobIsRefused()
    {
        assertFalse(trackerScheduler.triggerOnce("NoSuchTracker", CORE, 0L));
        assertFalse(trackerScheduler.triggerOnce(TRACKER, "archive", 0L));
    }

    @Test
    public void aTriggerFiredDuringARunIsHeldUntilTheRunEnds() throws Exception
    {
        tracker.release = new CountDownLatch(1);
        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 0L));
        awaitRun();

        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 0L));

        assertNull(tracker.runStarts.poll(500L, TimeUnit.MILLISECONDS));
        tracker.release.countDown();
        awaitRun();
        assertEquals(2, tracker.runs.get());
        assertEquals(1, tracker.maxRunning.get());
    }

    @Test
    public void theCallbacksSeeTheTriggeredRunsOnly() throws Exception
    {
        AtomicInteger started = new AtomicInteger();
        BlockingQueue<String> ended = new LinkedBlockingQueue<>();
        assertTrue(trackerScheduler.onTriggeredRun(TRACKER, CORE, started::incrementAndGet, () -> ended.add("ended")));

        trackerScheduler.scheduler.triggerJob(new JobKey(TRACKER + "-" + CORE, TrackerScheduler.SOLR_JOB_GROUP));
        awaitRun();
        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 0L));
        awaitRun();

        assertEquals("ended", ended.poll(10L, TimeUnit.SECONDS));
        assertNull(ended.poll(300L, TimeUnit.MILLISECONDS));
        assertEquals(1, started.get());
        assertEquals(2, tracker.runs.get());
    }

    @Test
    public void aFailingCallbackLeavesTheJobRunnable() throws Exception
    {
        assertTrue(trackerScheduler.onTriggeredRun(TRACKER, CORE,
                () -> {
                    throw new IllegalStateException("start callback bug");
                },
                () -> {
                    throw new AssertionError("end callback bug");
                }));

        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 0L));
        awaitRun();
        assertTrue(trackerScheduler.triggerOnce(TRACKER, CORE, 0L));
        awaitRun();

        assertEquals(2, tracker.runs.get());
    }

    @Test
    public void aCallbackForAnUnscheduledJobIsRefused()
    {
        assertFalse(trackerScheduler.onTriggeredRun("NoSuchTracker", CORE, () -> { }, () -> { }));
    }

    private long awaitRun() throws InterruptedException
    {
        Long started = tracker.runStarts.poll(10L, TimeUnit.SECONDS);
        assertNotNull("the job did not run", started);
        return started;
    }

    static final class RecordingTracker implements Tracker
    {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final BlockingQueue<Long> runStarts = new LinkedBlockingQueue<>();
        volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public void track()
        {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            runs.incrementAndGet();
            runStarts.add(System.nanoTime());
            try
            {
                release.await(10L, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                running.decrementAndGet();
            }
        }

        @Override
        public void maintenance()
        {
        }

        @Override
        public boolean hasMaintenance()
        {
            return false;
        }

        @Override
        public Semaphore getWriteLock()
        {
            return new Semaphore(1);
        }

        @Override
        public void setShutdown(boolean shutdown)
        {
        }

        @Override
        public boolean isAlreadyInShutDownMode()
        {
            return false;
        }

        @Override
        public void shutdown()
        {
        }

        @Override
        public boolean getRollback()
        {
            return false;
        }

        @Override
        public Throwable getRollbackCausedBy()
        {
            return null;
        }

        @Override
        public Properties getProps()
        {
            return new Properties();
        }

        @Override
        public void setRollback(boolean rollback, Throwable rollbackCausedBy)
        {
        }

        @Override
        public void invalidateState()
        {
        }

        @Override
        public TrackerState getTrackerState()
        {
            return null;
        }

        @Override
        public Type getType()
        {
            return Type.METADATA;
        }
    }
}
