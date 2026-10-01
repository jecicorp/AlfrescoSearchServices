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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.alfresco.indexing.config.TrackerProperties;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AwaitControllerTest
{
    private final AwaitService service = mock(AwaitService.class);
    private final TrackerProperties properties = new TrackerProperties();
    private final List<CapturingEmitter> emitters = new ArrayList<>();
    private final AwaitController controller = new AwaitController(service, properties, timeout -> {
        CapturingEmitter emitter = new CapturingEmitter(timeout);
        emitters.add(emitter);
        return emitter;
    });
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    private final AwaitHandle handle = new AwaitHandle("alfresco", Set.of(1234L), event -> { });
    private final List<List<String>> sentBeforeOpen = new ArrayList<>();

    @Before
    public void setUp()
    {
        when(service.open(anySet(), anyLong(), any(AwaitSink.class))).thenAnswer(invocation -> {
            if (!emitters.isEmpty())
            {
                sentBeforeOpen.add(List.copyOf(emitters.get(emitters.size() - 1).sent));
            }
            return handle;
        });
    }

    @Test
    public void aValidRequestOpensAStreamNoProxyMayBuffer() throws Exception
    {
        ResponseEntity<SseEmitter> response = controller.await(new AwaitRequest(List.of(1234L, 1235L), 20000L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertSame(emitters.get(0), response.getBody());
        assertEquals(Long.valueOf(30000L), emitters.get(0).getTimeout());
        verify(service).open(eq(Set.of(1234L, 1235L)), eq(20000L), any(AwaitSink.class));
        assertEquals(List.of(List.of(":open\n\n")), sentBeforeOpen);
    }

    @Test
    public void theHeadersAreFlushedBeforeTheFirstEvent() throws Exception
    {
        MockMvc real = MockMvcBuilders.standaloneSetup(new AwaitController(service, properties)).build();

        MockHttpServletResponse response = real.perform(post("/api/v1/index/await")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dbids\":[1234]}"))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();

        assertTrue(response.isCommitted());
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType(), response.getContentType().startsWith("text/event-stream"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(":open\n\n", response.getContentAsString());
    }

    @Test
    public void theEventsReachTheWireAsServerSentEvents() throws Exception
    {
        MockMvc real = MockMvcBuilders.standaloneSetup(new AwaitController(service, properties)).build();

        MockHttpServletResponse response = real.perform(post("/api/v1/index/await")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dbids\":[1234,1235]}"))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();
        ArgumentCaptor<AwaitSink> sink = ArgumentCaptor.forClass(AwaitSink.class);
        verify(service).open(anySet(), anyLong(), sink.capture());
        sink.getValue().accept(new AwaitEvent.Searchable(1234L));
        sink.getValue().accept(new AwaitEvent.Failed(1235L, "ORPHAN"));
        sink.getValue().accept(new AwaitEvent.End(List.of()));

        assertEquals(":open\n\n"
                + "event:searchable\ndata:{\"dbid\":1234}\n\n"
                + "event:error\ndata:{\"dbid\":1235,\"verdict\":\"ORPHAN\"}\n\n"
                + "event:end\ndata:{\"pending\":[]}\n\n", response.getContentAsString());
    }

    @Test
    public void springDoesNotFlushASilentSseEmitterOnItsOwn() throws Exception
    {
        MockMvc silent = MockMvcBuilders.standaloneSetup(new SilentController()).build();

        MockHttpServletResponse response = silent.perform(get("/silent"))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();

        assertFalse(response.isCommitted());
        assertEquals("", response.getContentAsString());
    }

    @Test
    public void theTimeoutDefaultsAndIsCapped() throws Exception
    {
        controller.await(new AwaitRequest(List.of(1L), null));
        verify(service).open(eq(Set.of(1L)), eq(20000L), any(AwaitSink.class));

        properties.getAwait().setMaxTimeout(10000L);
        controller.await(new AwaitRequest(List.of(2L), null));
        controller.await(new AwaitRequest(List.of(3L), 999999L));

        verify(service).open(eq(Set.of(2L)), eq(10000L), any(AwaitSink.class));
        verify(service).open(eq(Set.of(3L)), eq(10000L), any(AwaitSink.class));
        assertEquals(Long.valueOf(20000L), emitters.get(2).getTimeout());
    }

    @Test
    public void aClientThatGoesAwayCancelsItsWaiters() throws Exception
    {
        controller.await(new AwaitRequest(List.of(1234L), null));
        CapturingEmitter emitter = emitters.get(0);

        emitter.completion.run();
        emitter.timeoutCallback.run();
        emitter.error.accept(new IOException("connection reset"));

        verify(service, times(3)).cancel(handle);
    }

    @Test
    public void aSendThatFailsCancelsItsWaiters() throws Exception
    {
        controller.await(new AwaitRequest(List.of(1234L), null));
        ArgumentCaptor<AwaitSink> sink = ArgumentCaptor.forClass(AwaitSink.class);
        verify(service).open(anySet(), anyLong(), sink.capture());
        emitters.get(0).failing = true;

        sink.getValue().accept(new AwaitEvent.Searchable(1234L));

        verify(service).cancel(handle);
    }

    @Test
    public void aSendThatFailsBeforeTheRequestIsRegisteredStillCancelsIt() throws Exception
    {
        when(service.open(anySet(), anyLong(), any(AwaitSink.class))).thenAnswer(invocation -> {
            emitters.get(0).failing = true;
            invocation.getArgument(2, AwaitSink.class).accept(new AwaitEvent.Searchable(1234L));
            return handle;
        });

        controller.await(new AwaitRequest(List.of(1234L), null));

        verify(service).cancel(handle);
    }

    @Test
    public void anUnreadableBodyIsABadRequest() throws Exception
    {
        assertBadRequest("not json", AwaitController.UNREADABLE);
        assertBadRequest("{\"dbids\":\"twelve\"}", AwaitController.UNREADABLE);
        assertBadRequest("{\"dbids\":[\"abc\"]}", AwaitController.UNREADABLE);
        assertBadRequest("", AwaitController.UNREADABLE);
        assertBadRequest("null", AwaitController.UNREADABLE);
        verifyNoInteractions(service);
    }

    @Test
    public void aBodyBreakingTheContractIsABadRequest() throws Exception
    {
        assertBadRequest("{}", "dbids");
        assertBadRequest("{\"dbids\":[]}", "dbids");
        assertBadRequest("{\"dbids\":[12,0]}", "positive");
        assertBadRequest("{\"dbids\":[12,null]}", "positive");
        assertBadRequest("{\"dbids\":[12],\"timeout\":0}", "timeout");
        verifyNoInteractions(service);
    }

    @Test
    public void moreDbidsThanTheBatchIsABadRequest() throws Exception
    {
        properties.getAwait().setMaxBatch(2);

        assertBadRequest("{\"dbids\":[1,2,3]}", "At most 2 DBIDs per request, got 3.");
        verifyNoInteractions(service);
    }

    @Test
    public void aFullRegistryIsServiceUnavailable() throws Exception
    {
        when(service.open(anySet(), anyLong(), any(AwaitSink.class)))
                .thenThrow(new AwaitCapacityException("Too many nodes awaited at once: 10000 pending, 1 requested, at most 10000."));

        mvc.perform(post("/api/v1/index/await").contentType(MediaType.APPLICATION_JSON).content("{\"dbids\":[1]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("Too many nodes awaited at once")));
    }

    @Test
    public void withoutAWorkspaceCoreTheServiceIsUnavailable() throws Exception
    {
        when(service.open(anySet(), anyLong(), any(AwaitSink.class)))
                .thenThrow(new AwaitUnavailableException("No tracked core indexes workspace://SpacesStore."));

        mvc.perform(post("/api/v1/index/await").contentType(MediaType.APPLICATION_JSON).content("{\"dbids\":[1]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("No tracked core indexes")));
    }

    private void assertBadRequest(String body, String reason) throws Exception
    {
        mvc.perform(post("/api/v1/index/await").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString(reason)));
    }

    @RestController
    static class SilentController
    {
        @GetMapping("/silent")
        public ResponseEntity<SseEmitter> silent()
        {
            return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(new SseEmitter(60000L));
        }
    }

    private static final class CapturingEmitter extends SseEmitter
    {
        final List<String> sent = new ArrayList<>();
        Runnable completion;
        Runnable timeoutCallback;
        Consumer<Throwable> error;
        volatile boolean failing;

        CapturingEmitter(long timeout)
        {
            super(timeout);
        }

        @Override
        public void onCompletion(Runnable callback)
        {
            completion = callback;
            super.onCompletion(callback);
        }

        @Override
        public void onTimeout(Runnable callback)
        {
            timeoutCallback = callback;
            super.onTimeout(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback)
        {
            error = callback;
            super.onError(callback);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            if (failing)
            {
                throw new IOException("broken pipe");
            }
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build())
            {
                text.append(part.getData());
            }
            sent.add(text.toString());
        }
    }
}
