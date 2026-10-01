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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;

public class ScheduledDelaysTest
{
    private final ScheduledDelays delays = new ScheduledDelays(new ScheduledThreadPoolExecutor(1));

    @After
    public void tearDown()
    {
        delays.shutdown();
    }

    @Test
    public void aTaskRunsAfterItsDelay() throws Exception
    {
        CountDownLatch ran = new CountDownLatch(1);

        delays.schedule(ran::countDown, 50L);

        assertTrue(ran.await(5L, TimeUnit.SECONDS));
    }

    @Test
    public void aCancelledTaskNeverRuns() throws Exception
    {
        AtomicBoolean ran = new AtomicBoolean();

        delays.schedule(() -> ran.set(true), 200L).run();

        Thread.sleep(400L);
        assertFalse(ran.get());
    }

    @Test
    public void aStoppedTimerAcceptsNothingAndCancelsNothing()
    {
        delays.shutdown();

        delays.schedule(() -> { }, 50L).run();
    }

    @Test
    public void aTaskThrowingAnErrorDoesNotStopTheTimer() throws Exception
    {
        CountDownLatch ran = new CountDownLatch(1);

        delays.schedule(() -> {
            throw new AssertionError("boom");
        }, 10L);
        delays.schedule(ran::countDown, 100L);

        assertTrue(ran.await(5L, TimeUnit.SECONDS));
    }

    @Test
    public void theTimerThreadIsADaemon() throws Exception
    {
        ScheduledDelays daemonDelays = ScheduledDelays.daemon();
        AtomicBoolean daemon = new AtomicBoolean();
        CountDownLatch ran = new CountDownLatch(1);
        try
        {
            daemonDelays.schedule(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                ran.countDown();
            }, 10L);

            assertTrue(ran.await(5L, TimeUnit.SECONDS));
            assertTrue(daemon.get());
        }
        finally
        {
            daemonDelays.shutdown();
        }
    }

    @Test
    public void shutdownWaitsForARunningTaskToFinish() throws Exception
    {
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        delays.schedule(() -> {
            started.countDown();
            try
            {
                Thread.sleep(200L);
            }
            catch (InterruptedException e)
            {
                return;
            }
            finished.set(true);
        }, 0L);
        assertTrue(started.await(5L, TimeUnit.SECONDS));

        delays.shutdown();

        assertTrue(finished.get());
    }
}
