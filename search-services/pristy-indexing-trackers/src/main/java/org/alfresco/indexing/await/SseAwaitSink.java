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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Writes the events of one index await request to its SSE connection and completes it after {@code end}.
 */
public class SseAwaitSink implements AwaitSink
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SseAwaitSink.class);

    private final SseEmitter emitter;
    private final Runnable onBroken;

    /**
     * @param emitter  the connection of the request
     * @param onBroken called when a write fails, the client being gone
     */
    public SseAwaitSink(SseEmitter emitter, Runnable onBroken)
    {
        this.emitter = emitter;
        this.onBroken = onBroken;
    }

    @Override
    public void accept(AwaitEvent event)
    {
        try
        {
            emitter.send(SseEmitter.event().name(event.eventName()).data(event, MediaType.APPLICATION_JSON));
            if (event instanceof AwaitEvent.End)
            {
                emitter.complete();
            }
        }
        catch (IOException | IllegalStateException e)
        {
            LOGGER.debug("An index await stream closed before its {} event", event.eventName(), e);
            onBroken.run();
        }
    }
}
