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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pins that a tick publishes the state it has just sampled, not the previous one.
 */
public class ProgressSamplerTest
{
    @Test
    public void publishesTheStateItHasJustSampled()
    {
        List<TrackerReading> readings = new ArrayList<>();
        ProgressService service = new ProgressService(() -> readings, Clock.systemUTC(),
                Duration.ofSeconds(60));
        ProgressBroadcaster broadcaster = new ProgressBroadcaster(service);
        ProgressSampler sampler = new ProgressSampler(service, broadcaster);
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);

        readings.add(new TrackerReading("alfresco", "metadata", true, 10L, 5L, null));
        sampler.tick();

        ProgressSnapshot published = emitter.sent.get(emitter.sent.size() - 1);
        assertEquals(1, published.cores().size());
        assertEquals(Long.valueOf(5L), published.cores().get(0).trackers().get(0).remaining());
    }

    @Test
    public void survivesASourceThatIsNotReadyYet()
    {
        ProgressService service = new ProgressService(() -> {
            throw new IllegalStateException("core not started");
        }, Clock.systemUTC(), Duration.ofSeconds(60));
        ProgressSampler sampler = new ProgressSampler(service, new ProgressBroadcaster(service));

        sampler.tick();
    }

    private static final class RecordingEmitter extends SseEmitter
    {
        private final List<ProgressSnapshot> sent = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            builder.build().forEach(part -> {
                if (part.getData() instanceof ProgressSnapshot snapshot)
                {
                    sent.add(snapshot);
                }
            });
        }
    }
}
