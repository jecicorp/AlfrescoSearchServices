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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pins how the SSE stream serves its subscribers.
 */
public class ProgressBroadcasterTest
{
    private final ProgressService service = mock(ProgressService.class);

    @Test
    public void handsANewSubscriberTheCurrentStateWithoutWaitingForTheNextTick()
    {
        ProgressSnapshot current = snapshot();
        when(service.snapshot()).thenReturn(current);
        ProgressBroadcaster broadcaster = new ProgressBroadcaster(service);
        RecordingEmitter emitter = new RecordingEmitter();

        broadcaster.register(emitter);

        assertEquals(1, emitter.sent.size());
        assertSame(current, emitter.sent.get(0));
    }

    @Test
    public void reachesEverySubscriber()
    {
        when(service.snapshot()).thenReturn(snapshot());
        ProgressBroadcaster broadcaster = new ProgressBroadcaster(service);
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        broadcaster.register(first);
        broadcaster.register(second);

        ProgressSnapshot tick = snapshot();
        broadcaster.broadcast(tick);

        assertSame(tick, first.sent.get(1));
        assertSame(tick, second.sent.get(1));
    }

    @Test
    public void forgetsASubscriberWhoseConnectionIsGone()
    {
        when(service.snapshot()).thenReturn(snapshot());
        ProgressBroadcaster broadcaster = new ProgressBroadcaster(service);
        RecordingEmitter gone = new RecordingEmitter();
        gone.failing = true;
        broadcaster.register(gone);

        assertEquals("a send that throws must not leave the subscriber in the list",
                0, broadcaster.subscriberCount());

        broadcaster.broadcast(snapshot());

        assertEquals(0, broadcaster.subscriberCount());
    }

    private static ProgressSnapshot snapshot()
    {
        return new ProgressSnapshot(Instant.parse("2026-09-22T10:00:00Z"), List.of());
    }

    private static final class RecordingEmitter extends SseEmitter
    {
        private final List<Object> sent = new ArrayList<>();
        private boolean failing;

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            if (failing)
            {
                throw new IOException("broken pipe");
            }
            builder.build().forEach(part -> {
                if (part.getData() instanceof ProgressSnapshot snapshot)
                {
                    sent.add(snapshot);
                }
            });
        }
    }
}
