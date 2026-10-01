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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

import org.alfresco.indexing.config.TrackerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves {@code POST /api/v1/index/await}: a stream that ends once the given nodes are searchable.
 */
@RestController
@RequestMapping("/api/v1/index")
@ConditionalOnProperty(prefix = "alfresco.tracker.await", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AwaitController
{
    static final long EMITTER_GRACE_MILLIS = 10000L;
    static final String OPEN_COMMENT = "open";
    static final String UNREADABLE = "The request body is not a readable await request.";

    private final AwaitService service;
    private final TrackerProperties properties;
    private final LongFunction<SseEmitter> emitters;

    /**
     * @param service    the index await service
     * @param properties the {@code alfresco.tracker.await} limits
     */
    @Autowired
    public AwaitController(AwaitService service, TrackerProperties properties)
    {
        this(service, properties, SseEmitter::new);
    }

    AwaitController(AwaitService service, TrackerProperties properties, LongFunction<SseEmitter> emitters)
    {
        this.service = service;
        this.properties = properties;
        this.emitters = emitters;
    }

    /**
     * @param request the DBIDs to wait for and the timeout
     * @return a stream opened by an {@code :open} comment, then {@code searchable}, {@code error} and {@code end} events
     * @throws IOException when the opening comment cannot be queued
     */
    @PostMapping(path = "/await", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SseEmitter> await(@RequestBody AwaitRequest request) throws IOException
    {
        TrackerProperties.AwaitConfig config = properties.getAwait();
        AwaitRequest.Accepted accepted = request.accept(config.getMaxTimeout(), config.getMaxBatch());
        SseEmitter emitter = emitters.apply(accepted.timeoutMillis() + EMITTER_GRACE_MILLIS);
        emitter.send(SseEmitter.event().comment(OPEN_COMMENT));
        AtomicReference<AwaitHandle> opened = new AtomicReference<>();
        AtomicBoolean broken = new AtomicBoolean();
        Runnable cancel = () -> {
            broken.set(true);
            AwaitHandle handle = opened.get();
            if (handle != null)
            {
                service.cancel(handle);
            }
        };
        AwaitHandle handle = service.open(accepted.dbids(), accepted.timeoutMillis(), new SseAwaitSink(emitter, cancel));
        opened.set(handle);
        if (broken.get())
        {
            service.cancel(handle);
        }
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** @return {@code 400} with the reason the request breaks the contract */
    @ExceptionHandler(InvalidAwaitRequestException.class)
    public ResponseEntity<String> invalid(InvalidAwaitRequestException e)
    {
        return plain(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** @return {@code 400} for a body that is not an await request */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadable(HttpMessageNotReadableException e)
    {
        return plain(HttpStatus.BAD_REQUEST, UNREADABLE);
    }

    /** @return {@code 503} with the reason the request cannot be served now */
    @ExceptionHandler({AwaitCapacityException.class, AwaitUnavailableException.class})
    public ResponseEntity<String> unavailable(RuntimeException e)
    {
        return plain(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<String> plain(HttpStatus status, String reason)
    {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(reason);
    }
}
