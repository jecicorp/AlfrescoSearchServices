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

import org.alfresco.indexing.config.TrackerProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves the index diagnostic job: start or join, cancel, read, and follow as a stream.
 */
@RestController
@RequestMapping("/api/v1/index/diagnostic")
public class DiagnosticController
{
    private final DiagnosticJobService service;
    private final DiagnosticBroadcaster broadcaster;
    private final TrackerProperties properties;

    public DiagnosticController(DiagnosticJobService service, DiagnosticBroadcaster broadcaster,
            TrackerProperties properties)
    {
        this.service = service;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    /**
     * @param user who asks, recorded as {@code startedBy}
     * @return {@code 202} and the running job, the one already running when there was one
     */
    @PostMapping
    public ResponseEntity<DiagnosticSnapshot> start(@RequestParam(value = "user", required = false) String user)
    {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(user));
    }

    /** @return {@code 202} and the job being cancelled, or {@code 409} and the current state when nothing runs */
    @DeleteMapping
    public ResponseEntity<DiagnosticSnapshot> cancel()
    {
        return service.cancel()
                .map(snapshot -> ResponseEntity.status(HttpStatus.ACCEPTED).body(snapshot))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(service.snapshot()));
    }

    /** @return the current state of the job, with the last result */
    @GetMapping
    public DiagnosticSnapshot snapshot()
    {
        return service.snapshot();
    }

    /** @return a stream of {@code state} events, the first one sent on connection */
    @GetMapping("/stream")
    public ResponseEntity<SseEmitter> stream()
    {
        SseEmitter emitter = new SseEmitter(properties.getDiagnostic().getStreamTimeoutMillis());
        broadcaster.register(emitter);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
