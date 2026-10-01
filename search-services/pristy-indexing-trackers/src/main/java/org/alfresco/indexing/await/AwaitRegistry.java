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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The index await requests in progress and the nodes they wait for, under one lock that never covers any I/O.
 */
public class AwaitRegistry
{
    private final int maxWaiters;
    private final Consumer<Delivery> outbox;
    private final Set<AwaitHandle> handles = new LinkedHashSet<>();
    private int pending;

    /**
     * @param maxWaiters the most nodes awaited at once across every request
     * @param outbox     receives the events in order, under the lock, once the state is updated; it must only enqueue
     */
    public AwaitRegistry(int maxWaiters, Consumer<Delivery> outbox)
    {
        this.maxWaiters = maxWaiters;
        this.outbox = outbox;
    }

    /**
     * @param core  the core the nodes are awaited on
     * @param dbids the distinct DBIDs of the request
     * @param sink  where the events of the request go
     * @return the request, waiting for every DBID
     * @throws AwaitCapacityException when accepting the DBIDs would exceed the capacity
     */
    public synchronized AwaitHandle open(String core, Set<Long> dbids, AwaitSink sink)
    {
        if (pending + dbids.size() > maxWaiters)
        {
            throw new AwaitCapacityException(String.format(
                    "Too many nodes awaited at once: %d pending, %d requested, at most %d.",
                    pending, dbids.size(), maxWaiters));
        }
        AwaitHandle handle = new AwaitHandle(core, dbids, sink);
        handles.add(handle);
        pending += dbids.size();
        return handle;
    }

    /** Records the transaction a node of the request must reach in the index before it counts as searchable. */
    public synchronized void watch(AwaitHandle handle, long dbid, long requiredTx)
    {
        if (!handle.closed && handle.remaining.contains(dbid))
        {
            handle.requiredTx.put(dbid, requiredTx);
        }
    }

    /** Releases a node of the request that will not become searchable, with the verdict saying why. */
    public synchronized void fail(AwaitHandle handle, long dbid, String verdict)
    {
        List<Delivery> out = new ArrayList<>();
        release(handle, dbid, new AwaitEvent.Failed(dbid, verdict), out);
        send(out);
    }

    /** @return the DBIDs the request watches and still waits for */
    public synchronized Set<Long> watched(AwaitHandle handle)
    {
        return handle.closed ? Set.of() : Set.copyOf(handle.requiredTx.keySet());
    }

    /** @return the DBIDs any request watches on {@code core} */
    public synchronized Set<Long> watched(String core)
    {
        Set<Long> watched = new HashSet<>();
        for (AwaitHandle handle : handles)
        {
            if (handle.core.equals(core))
            {
                watched.addAll(handle.requiredTx.keySet());
            }
        }
        return watched;
    }

    /** @return whether any request watches a node on {@code core} */
    public synchronized boolean hasWatched(String core)
    {
        for (AwaitHandle handle : handles)
        {
            if (handle.core.equals(core) && !handle.requiredTx.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /** Releases every watched node of {@code core} whose documents make it searchable or failed. */
    public synchronized void settle(String core, Map<Long, List<IndexedDocument>> documents)
    {
        List<Delivery> out = new ArrayList<>();
        for (AwaitHandle handle : List.copyOf(handles))
        {
            if (!handle.core.equals(core))
            {
                continue;
            }
            for (Map.Entry<Long, Long> watched : List.copyOf(handle.requiredTx.entrySet()))
            {
                long dbid = watched.getKey();
                Readiness.decide(dbid, watched.getValue(), documents.getOrDefault(dbid, List.of()))
                        .ifPresent(event -> release(handle, dbid, event, out));
            }
        }
        send(out);
    }

    /** Ends a request whose timeout expired, reporting the nodes it still waits for. */
    public synchronized void expire(AwaitHandle handle)
    {
        if (handle.closed)
        {
            return;
        }
        List<Delivery> out = new ArrayList<>();
        end(handle, out);
        send(out);
    }

    /** Forgets a request whose client went away, without any event. */
    public synchronized void cancel(AwaitHandle handle)
    {
        if (!handle.closed)
        {
            close(handle);
        }
    }

    /** @return how many nodes are awaited across every request */
    public synchronized int pending()
    {
        return pending;
    }

    private void release(AwaitHandle handle, long dbid, AwaitEvent event, List<Delivery> out)
    {
        if (handle.closed || !handle.remaining.remove(dbid))
        {
            return;
        }
        handle.requiredTx.remove(dbid);
        pending--;
        out.add(new Delivery(handle, event));
        if (handle.remaining.isEmpty())
        {
            end(handle, out);
        }
    }

    private void end(AwaitHandle handle, List<Delivery> out)
    {
        out.add(new Delivery(handle, new AwaitEvent.End(List.copyOf(handle.remaining))));
        close(handle);
    }

    private void close(AwaitHandle handle)
    {
        handle.closed = true;
        pending -= handle.remaining.size();
        handle.remaining.clear();
        handle.requiredTx.clear();
        handles.remove(handle);
        handle.stopTimeout();
    }

    private void send(List<Delivery> out)
    {
        for (Delivery delivery : out)
        {
            outbox.accept(delivery);
        }
    }
}
