/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.api;

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
     * A reference matching no node is answered with the {@code unresolved}
     * verdict and HTTP 200, never 404: 404 on this path is reserved for a
     * trackers service that predates this API, so that a caller can tell the
     * two apart.
     *
     * @param ref a node DBID, a node UUID, or a full node reference
     * @return the node's indexing status across every tracked core
     */
    @GetMapping("/node")
    public NodeIndexStatus node(@RequestParam("ref") String ref)
    {
        return indexStatusService.status(ref);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badReference(IllegalArgumentException e)
    {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
