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

package org.alfresco.indexing.progress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans one snapshot out to every open SSE connection.
 */
public class ProgressBroadcaster
{
    private final ProgressService service;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public ProgressBroadcaster(ProgressService service)
    {
        this.service = service;
    }

    /**
     * Registers a connection and serves it the current state at once, so a client never
     * has to poll for its initial view before the next tick arrives.
     *
     * @param emitter the connection to serve
     */
    public void register(SseEmitter emitter)
    {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        send(emitter, service.snapshot());
    }

    /**
     * @param snapshot the state to push to every open connection
     */
    public void broadcast(ProgressSnapshot snapshot)
    {
        for (SseEmitter emitter : subscribers)
        {
            send(emitter, snapshot);
        }
    }

    /** @return how many connections are open */
    public int subscriberCount()
    {
        return subscribers.size();
    }

    private void send(SseEmitter emitter, ProgressSnapshot snapshot)
    {
        try
        {
            emitter.send(SseEmitter.event().name("progress").data(snapshot));
        }
        catch (Exception e)
        {
            subscribers.remove(emitter);
        }
    }
}
