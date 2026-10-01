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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseAwaitSinkTest
{
    private final RecordingEmitter emitter = new RecordingEmitter();
    private final AtomicInteger broken = new AtomicInteger();
    private final SseAwaitSink sink = new SseAwaitSink(emitter, broken::incrementAndGet);

    @Test
    public void eachEventIsNamedAndCarriesItsDataAsJson()
    {
        sink.accept(new AwaitEvent.Searchable(1234L));
        sink.accept(new AwaitEvent.Failed(1235L, "ERROR"));

        assertEquals(List.of("searchable", "error"), emitter.names);
        assertEquals(List.of(new AwaitEvent.Searchable(1234L), new AwaitEvent.Failed(1235L, "ERROR")), emitter.data);
        assertEquals(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON), emitter.mediaTypes);
        assertFalse(emitter.completed);
    }

    @Test
    public void theEndCompletesTheStream()
    {
        sink.accept(new AwaitEvent.End(List.of(1235L)));

        assertEquals(List.of("end"), emitter.names);
        assertTrue(emitter.completed);
        assertEquals(0, broken.get());
    }

    @Test
    public void aSendThatFailsReportsABrokenStream()
    {
        emitter.failure = new IOException("broken pipe");

        sink.accept(new AwaitEvent.Searchable(1234L));

        assertEquals(1, broken.get());
    }

    @Test
    public void aSendOnACompletedStreamReportsABrokenStream()
    {
        emitter.failure = new IllegalStateException("ResponseBodyEmitter has already completed");

        sink.accept(new AwaitEvent.End(List.of()));

        assertEquals(1, broken.get());
        assertFalse(emitter.completed);
    }

    private static final class RecordingEmitter extends SseEmitter
    {
        final List<String> names = new ArrayList<>();
        final List<Object> data = new ArrayList<>();
        final List<MediaType> mediaTypes = new ArrayList<>();
        Exception failure;
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            if (failure instanceof IOException io)
            {
                throw io;
            }
            if (failure instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build())
            {
                if (part.getData() instanceof String line)
                {
                    text.append(line);
                }
                else
                {
                    data.add(part.getData());
                    mediaTypes.add(part.getMediaType());
                }
            }
            Matcher name = Pattern.compile("event:(\\w+)").matcher(text);
            if (name.find())
            {
                names.add(name.group(1));
            }
        }

        @Override
        public void complete()
        {
            completed = true;
        }
    }
}
