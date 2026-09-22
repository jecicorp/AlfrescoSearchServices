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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class ProgressControllerTest
{
    private final ProgressService service = mock(ProgressService.class);
    private final ProgressBroadcaster broadcaster = new ProgressBroadcaster(service);
    private final ProgressController controller = new ProgressController(service, broadcaster, 0L);

    @Test
    public void servesTheCurrentSnapshotForDiagnosis()
    {
        ProgressSnapshot snapshot = new ProgressSnapshot(Instant.now(), List.of());
        when(service.snapshot()).thenReturn(snapshot);

        assertSame(snapshot, controller.progress());
    }

    @Test
    public void opensAnEventStreamThatNoProxyMayBuffer()
    {
        when(service.snapshot()).thenReturn(new ProgressSnapshot(Instant.now(), List.of()));

        ResponseEntity<SseEmitter> response = controller.stream();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("nginx buffers proxied responses by default, which stalls SSE",
                "no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertNotNull(response.getBody());
    }

    @Test
    public void subscribesTheNewStreamToTheBroadcaster()
    {
        when(service.snapshot()).thenReturn(new ProgressSnapshot(Instant.now(), List.of()));

        controller.stream();

        assertEquals(1, broadcaster.subscriberCount());
    }
}
