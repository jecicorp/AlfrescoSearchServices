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

import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.alfresco.indexing.config.TrackerProperties;
import org.junit.After;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class DiagnosticControllerTest
{
    private final DiagnosticJobService service = mock(DiagnosticJobService.class);
    private final DiagnosticBroadcaster broadcaster = new DiagnosticBroadcaster(service::snapshot, 500L, 30000L);
    private final DiagnosticController controller =
            new DiagnosticController(service, broadcaster, new TrackerProperties());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @After
    public void stopBroadcaster()
    {
        broadcaster.shutdown();
    }

    @Test
    public void postStartsOrJoinsAndAnswersAccepted() throws Exception
    {
        when(service.start("admin")).thenReturn(running());

        mvc.perform(post("/api/v1/index/diagnostic").param("user", "admin"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.startedBy").value("admin"))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()));
    }

    @Test
    public void deleteWhileRunningAnswersAccepted() throws Exception
    {
        when(service.cancel()).thenReturn(Optional.of(running()));

        mvc.perform(delete("/api/v1/index/diagnostic"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("running"));
    }

    @Test
    public void deleteWithNothingRunningAnswersConflict() throws Exception
    {
        when(service.cancel()).thenReturn(Optional.empty());
        when(service.snapshot()).thenReturn(DiagnosticSnapshot.idle());

        mvc.perform(delete("/api/v1/index/diagnostic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("idle"));
    }

    @Test
    public void getAnswersTheSnapshot() throws Exception
    {
        when(service.snapshot()).thenReturn(DiagnosticSnapshot.idle());

        mvc.perform(get("/api/v1/index/diagnostic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("idle"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    public void theStreamIsAnEventStreamNoProxyMayBuffer()
    {
        when(service.snapshot()).thenReturn(DiagnosticSnapshot.idle());

        ResponseEntity<SseEmitter> response = controller.stream();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNotNull(response.getBody());
        assertEquals(1, broadcaster.subscriberCount());
    }

    @Test
    public void theStreamDefaultsFollowTheContract()
    {
        TrackerProperties.DiagnosticConfig defaults = new TrackerProperties().getDiagnostic();

        assertEquals(30000L, defaults.getKeepaliveMillis());
        assertEquals(500L, defaults.getMinEventIntervalMillis());
        assertEquals(0L, defaults.getStreamTimeoutMillis());
    }

    private static DiagnosticSnapshot running()
    {
        return DiagnosticSnapshot.running("2026-09-25T15:02:11Z", "admin", List.of("alfresco"), null);
    }
}
