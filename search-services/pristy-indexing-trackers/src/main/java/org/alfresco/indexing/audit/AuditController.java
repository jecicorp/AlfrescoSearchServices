/*-
 * #%L
 * Pristy Indexing Trackers
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
package org.alfresco.indexing.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Audit consultation API, version 1. The caller supplies its own authority set; the
 * trackers service does not authenticate it, so this surface must stay inside the stack.
 */
@ConditionalOnProperty(prefix = "alfresco.tracker.audit", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController
{
    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService)
    {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/events")
    public AuditPage events(
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String nodeUuid,
            @RequestParam(required = false) String pathPrefix,
            @RequestParam(required = false) Long fromMs,
            @RequestParam(required = false) Long toMs,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) List<String> authority,
            @RequestParam(defaultValue = "false") boolean unfiltered,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int rows,
            @RequestParam(defaultValue = "0") int start)
    {
        return auditQueryService.search(new AuditQuery(principal, eventType, nodeUuid, pathPrefix,
                fromMs, toMs, folder, authority, unfiltered, cursor, Math.min(Math.max(rows, 1), 1000), Math.max(start, 0)));
    }

    @GetMapping("/facets")
    public Map<String, Long> facets(
            @RequestParam String field,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) List<String> authority,
            @RequestParam(defaultValue = "false") boolean unfiltered,
            @RequestParam(defaultValue = "100") int limit)
    {
        return auditQueryService.facet(new AuditQuery(null, null, null, null, null, null, folder,
                authority, unfiltered, null, 0, 0), field, limit);
    }

    @GetMapping("/contributors")
    public Map<String, Long> contributors(
            @RequestParam String folder,
            @RequestParam(required = false) List<String> authority,
            @RequestParam(defaultValue = "false") boolean unfiltered)
    {
        return auditQueryService.contributors(new AuditQuery(null, null, null, null, null, null, folder,
                authority, unfiltered, null, 0, 0));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(RuntimeException e)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
