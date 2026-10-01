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

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs delayed tasks on a {@link ScheduledExecutorService}.
 */
public class ScheduledDelays implements Delays
{
    private static final long TERMINATION_WAIT_MILLIS = 1000L;

    private final ScheduledExecutorService executor;

    /**
     * @param executor the timer thread, owned by this object from now on
     */
    public ScheduledDelays(ScheduledExecutorService executor)
    {
        this.executor = executor;
    }

    /**
     * @return delays running on a single daemon thread
     */
    public static ScheduledDelays daemon()
    {
        return new ScheduledDelays(Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "index-await-delays");
            thread.setDaemon(true);
            return thread;
        }));
    }

    @Override
    public Runnable schedule(Runnable task, long delayMillis)
    {
        try
        {
            ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
        catch (RejectedExecutionException e)
        {
            return () -> { };
        }
    }

    @Override
    public void shutdown()
    {
        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS))
            {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
