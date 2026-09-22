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

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves indexing progress, as a snapshot and as a stream.
 */
@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController
{
    private final ProgressService service;
    private final ProgressBroadcaster broadcaster;
    private final long streamTimeoutMillis;

    public ProgressController(ProgressService service, ProgressBroadcaster broadcaster,
            long streamTimeoutMillis)
    {
        this.service = service;
        this.broadcaster = broadcaster;
        this.streamTimeoutMillis = streamTimeoutMillis;
    }

    /** @return the current progress of every tracker */
    @GetMapping
    public ProgressSnapshot progress()
    {
        return service.snapshot();
    }

    /**
     * The response carries {@code X-Accel-Buffering: no} so that an nginx sitting in
     * front of the service stops buffering this one response, which is otherwise enough
     * to make the stream arrive only once the connection closes.
     *
     * @return a stream pushing one event per sampling tick
     */
    @GetMapping("/stream")
    public ResponseEntity<SseEmitter> stream()
    {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        broadcaster.register(emitter);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
