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

package org.alfresco.indexing.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pristy indexing API, version 1. Additive to the Solr-compat admin surface,
 * which keeps its own contract unchanged.
 */
@RestController
@RequestMapping("/api/v1/index")
public class IndexStatusController
{
    private final IndexStatusService indexStatusService;

    public IndexStatusController(IndexStatusService indexStatusService)
    {
        this.indexStatusService = indexStatusService;
    }

    /**
     * A reference that resolves to no node is answered {@code 404}, carrying the same
     * body as any other answer so the caller still gets the diagnosis. A node that is
     * merely absent from every core is {@code 200}: it resolved, and "known to the
     * repository, missing from the index" is the answer, not a missing resource.
     *
     * @param ref a node DBID, a node UUID, or a full node reference
     * @return the node's indexing status across every tracked core
     */
    @GetMapping("/node")
    public ResponseEntity<NodeIndexStatus> node(@RequestParam("ref") String ref)
    {
        NodeIndexStatus status = indexStatusService.status(ref);
        HttpStatus code = status.verdict() == NodeIndexStatus.Verdict.UNRESOLVED
                ? HttpStatus.NOT_FOUND
                : HttpStatus.OK;
        return ResponseEntity.status(code).body(status);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badReference(IllegalArgumentException e)
    {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
