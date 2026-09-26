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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans the diagnostic snapshots out to every open SSE connection. A publish is only a trigger: the
 * snapshot actually sent is always read from the supplier at send time, never the argument passed to
 * {@link #publish(DiagnosticSnapshot)}. A running snapshot is throttled to at most one per flush, any
 * other state is sent at once, and a keepalive comment runs on its own cadence.
 */
public class DiagnosticBroadcaster
{
    static final String EVENT_NAME = "state";
    static final String KEEPALIVE = "keepalive";

    private final Supplier<DiagnosticSnapshot> current;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private boolean pending;

    public DiagnosticBroadcaster(Supplier<DiagnosticSnapshot> current)
    {
        this.current = current;
    }

    /**
     * Registers a connection and serves it the current snapshot at once.
     *
     * @param emitter the connection to serve
     */
    public void register(SseEmitter emitter)
    {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        synchronized (this)
        {
            send(emitter, current.get());
        }
    }

    /**
     * Triggers a broadcast of the supplier's current snapshot: queued for the next flush while it is
     * running, sent at once for any other state.
     *
     * @param snapshot ignored; kept so a {@code DiagnosticJobService} listener can call it directly
     */
    public synchronized void publish(DiagnosticSnapshot snapshot)
    {
        DiagnosticSnapshot actual = current.get();
        if (DiagnosticSnapshot.RUNNING.equals(actual.state()))
        {
            pending = true;
            return;
        }
        pending = false;
        broadcast(actual);
    }

    /** Sends the supplier's current snapshot when a running publish is still queued. */
    public synchronized void flush()
    {
        if (pending)
        {
            pending = false;
            broadcast(current.get());
        }
    }

    /** Sends a {@code :keepalive} comment so an idle proxy keeps every connection open. */
    public synchronized void keepalive()
    {
        for (SseEmitter emitter : subscribers)
        {
            try
            {
                emitter.send(SseEmitter.event().comment(KEEPALIVE));
            }
            catch (Exception e)
            {
                subscribers.remove(emitter);
            }
        }
    }

    /** @return how many connections are open */
    public int subscriberCount()
    {
        return subscribers.size();
    }

    private void broadcast(DiagnosticSnapshot snapshot)
    {
        for (SseEmitter emitter : subscribers)
        {
            send(emitter, snapshot);
        }
    }

    private void send(SseEmitter emitter, DiagnosticSnapshot snapshot)
    {
        try
        {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(snapshot));
        }
        catch (Exception e)
        {
            subscribers.remove(emitter);
        }
    }
}
