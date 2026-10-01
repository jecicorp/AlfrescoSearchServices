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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.alfresco.indexing.api.NodeIndexStatus.DatabaseStatus;
import org.alfresco.indexing.api.NodeIndexStatus.Verdict;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.tracker.CommitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds index await requests until their nodes are searchable, waking the metadata and commit trackers of the core.
 */
public class AwaitService implements CommitListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AwaitService.class);

    static final long RECHECK_DELAY_MILLIS = 1000L;
    static final long STALE_TRIGGER_MILLIS = 60000L;
    private static final long SHUTDOWN_WAIT_MILLIS = 1000L;

    private final Settings settings;
    private final CoreTrackers trackers;
    private final DatabaseReader database;
    private final IndexProbe index;
    private final Executor repository;
    private final Executor worker;
    private final Executor sender;
    private final Delays delays;
    private final LongSupplier clock;
    private final AwaitRegistry registry;
    private final AtomicLong pendingTriggerSince = new AtomicLong();
    private final AtomicBoolean hooked = new AtomicBoolean();
    private final AtomicBoolean recheckScheduled = new AtomicBoolean();

    /**
     * @param settings the awaited core, its lag and the capacity
     * @param trackers the trackers of the core
     * @param database reads the awaited nodes from the repository
     * @param index      reads the awaited nodes from the index
     * @param repository runs every repository read, one at a time
     * @param worker     runs every Solr call, one at a time
     * @param sender     writes the events, one at a time
     * @param delays     runs the timeouts and the rechecks
     * @param clock      the current time, in milliseconds
     */
    public AwaitService(Settings settings, CoreTrackers trackers, DatabaseReader database, IndexProbe index,
            Executor repository, Executor worker, Executor sender, Delays delays, LongSupplier clock)
    {
        this.settings = settings;
        this.trackers = trackers;
        this.database = database;
        this.index = index;
        this.repository = repository;
        this.worker = worker;
        this.sender = sender;
        this.delays = delays;
        this.clock = clock;
        this.registry = new AwaitRegistry(settings.maxWaiters(), this::deliver);
    }

    /**
     * Registers a request and returns at once; the repository and the index are read on their own threads.
     *
     * @param dbids         the distinct DBIDs to wait for
     * @param timeoutMillis how long to wait before {@code end}
     * @param sink          where the events go
     * @return the request
     * @throws AwaitCapacityException     when accepting the DBIDs would exceed {@code max-waiters}
     * @throws AwaitUnavailableException  when no tracked core indexes {@code workspace://SpacesStore}
     */
    public AwaitHandle open(Set<Long> dbids, long timeoutMillis, AwaitSink sink)
    {
        if (settings.core() == null)
        {
            throw new AwaitUnavailableException("No tracked core indexes " + TrackerProperties.WORKSPACE_STORE + ".");
        }
        AwaitHandle handle = registry.open(settings.core(), dbids, sink);
        handle.setTimeoutCancel(delays.schedule(guarded(() -> registry.expire(handle)), timeoutMillis));
        List<Long> requested = List.copyOf(dbids);
        submit(repository, () -> resolve(handle, requested));
        return handle;
    }

    /**
     * @param handle a request whose client went away; its nodes stop counting against the capacity
     */
    public void cancel(AwaitHandle handle)
    {
        registry.cancel(handle);
    }

    @Override
    public void afterCommit(String coreName)
    {
        if (settings.core() != null && settings.core().equals(coreName))
        {
            submit(worker, this::checkAfterCommit);
        }
    }

    /** Stops the repository, worker, sender and timer threads. */
    public void shutdown()
    {
        stop(repository);
        stop(worker);
        stop(sender);
        delays.shutdown();
    }

    int pendingWaiters()
    {
        return registry.pending();
    }

    private void resolve(AwaitHandle handle, List<Long> dbids)
    {
        hook();
        Map<Long, DatabaseNode> nodes = database.read(dbids);
        for (Long dbid : dbids)
        {
            DatabaseNode node = nodes.get(dbid);
            if (node == null || node.status() == DatabaseNode.Status.UNREACHABLE)
            {
                registry.fail(handle, dbid, DatabaseStatus.UNREACHABLE.name());
            }
            else if (node.status() == DatabaseNode.Status.ORPHAN)
            {
                registry.fail(handle, dbid, Verdict.ORPHAN.name());
            }
            else
            {
                registry.watch(handle, dbid, node.tx());
            }
        }
        submit(worker, () -> checkResolved(handle));
    }

    private void checkResolved(AwaitHandle handle)
    {
        check(registry.watched(handle));
        if (!registry.watched(handle).isEmpty())
        {
            requestTrigger();
        }
    }

    private void hook()
    {
        if (!hooked.compareAndSet(false, true))
        {
            return;
        }
        boolean done = false;
        try
        {
            done = trackers.hook(settings.core(), this::metadataRunStarted, this::metadataRunEnded, this);
        }
        finally
        {
            if (!done)
            {
                hooked.set(false);
            }
        }
    }

    private void check(Set<Long> dbids)
    {
        if (dbids.isEmpty())
        {
            return;
        }
        Map<Long, List<IndexedDocument>> documents;
        try
        {
            documents = index.find(settings.core(), dbids);
        }
        catch (IOException e)
        {
            LOGGER.warn("Could not check {} awaited nodes on core {}", dbids.size(), settings.core(), e);
            return;
        }
        registry.settle(settings.core(), documents);
    }

    private void checkAfterCommit()
    {
        check(registry.watched(settings.core()));
        if (registry.hasWatched(settings.core()) && recheckScheduled.compareAndSet(false, true))
        {
            delays.schedule(guarded(() -> submit(worker, this::recheck)), RECHECK_DELAY_MILLIS);
        }
    }

    private void recheck()
    {
        recheckScheduled.set(false);
        check(registry.watched(settings.core()));
    }

    private void requestTrigger()
    {
        long now = clock.getAsLong();
        long since = pendingTriggerSince.get();
        if (since != 0L && now - since < settings.lagMillis() + STALE_TRIGGER_MILLIS)
        {
            return;
        }
        if (pendingTriggerSince.compareAndSet(since, now)
                && !trackers.triggerMetadata(settings.core(), settings.lagMillis()))
        {
            pendingTriggerSince.compareAndSet(now, 0L);
        }
    }

    private void metadataRunStarted()
    {
        pendingTriggerSince.set(0L);
    }

    private void metadataRunEnded()
    {
        submit(worker, () -> {
            if (registry.hasWatched(settings.core()))
            {
                trackers.triggerCommit(settings.core());
            }
        });
    }

    private void deliver(Delivery delivery)
    {
        submit(sender, () -> delivery.handle().sink().accept(delivery.event()));
    }

    private void submit(Executor executor, Runnable task)
    {
        try
        {
            executor.execute(guarded(task));
        }
        catch (RejectedExecutionException e)
        {
            LOGGER.debug("The index await service is shut down", e);
        }
    }

    private static Runnable guarded(Runnable task)
    {
        return () -> {
            try
            {
                task.run();
            }
            catch (Error e)
            {
                LOGGER.error("An index await task failed", e);
                throw e;
            }
            catch (Throwable e)
            {
                LOGGER.warn("An index await task failed", e);
            }
        };
    }

    private static void stop(Executor executor)
    {
        if (executor instanceof ExecutorService service)
        {
            service.shutdown();
            try
            {
                if (!service.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS))
                {
                    service.shutdownNow();
                }
            }
            catch (InterruptedException e)
            {
                service.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * What the service is bound to.
     *
     * @param core       the core tracking {@code workspace://SpacesStore}, {@code null} when none does
     * @param lagMillis  the core's {@code lag}, the delay of a metadata trigger
     * @param maxWaiters the most nodes awaited at once
     */
    public record Settings(String core, long lagMillis, int maxWaiters)
    {
    }
}
