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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class IndexStatusControllerTest
{
    private IndexStatusService service;
    private IndexStatusController controller;

    @Before
    public void setUp()
    {
        service = mock(IndexStatusService.class);
        controller = new IndexStatusController(service);
    }

    @Test
    public void aReferenceThatResolvesToNothingIsNotFound()
    {
        when(service.status("15695")).thenReturn(status(NodeIndexStatus.Verdict.UNRESOLVED));

        ResponseEntity<NodeIndexStatus> response = controller.node("15695");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull("the diagnosis must travel with the 404", response.getBody());
        assertEquals(NodeIndexStatus.Verdict.UNRESOLVED, response.getBody().verdict());
    }

    @Test
    public void aResolvedNodeMissingFromEveryCoreIsStillAnAnswer()
    {
        when(service.status("15695")).thenReturn(status(NodeIndexStatus.Verdict.MISSING));

        ResponseEntity<NodeIndexStatus> response = controller.node("15695");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(NodeIndexStatus.Verdict.MISSING, response.getBody().verdict());
    }

    @Test
    public void anIndexedNodeIsOk()
    {
        when(service.status("15695")).thenReturn(status(NodeIndexStatus.Verdict.INDEXED));

        assertEquals(HttpStatus.OK, controller.node("15695").getStatusCode());
    }

    @Test
    public void aMalformedReferenceIsABadRequestCarryingTheReason()
    {
        ResponseEntity<String> response =
                controller.badReference(new IllegalArgumentException("Expected a numeric DBID"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Expected a numeric DBID", response.getBody());
    }

    private static NodeIndexStatus status(NodeIndexStatus.Verdict verdict)
    {
        return new NodeIndexStatus(
                new NodeIndexStatus.Reference("15695", 15695L, "DBID"),
                new NodeIndexStatus.Database(NodeIndexStatus.DatabaseStatus.UNKNOWN, null),
                Map.of(), verdict);
    }
}
