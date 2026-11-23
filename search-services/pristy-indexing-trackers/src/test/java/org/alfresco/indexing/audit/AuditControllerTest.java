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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class AuditControllerTest
{
    private final AuditQueryService auditQueryService = mock(AuditQueryService.class);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(auditQueryService))
            .build();

    @Test
    public void bindsEveryEventQueryParameterByItsPublishedName() throws Exception
    {
        when(auditQueryService.search(any())).thenReturn(new AuditPage(List.of(), 0L, null));

        mockMvc.perform(get("/api/v1/audit/events")
                .param("principal", "abeecher")
                .param("eventType", "content-read")
                .param("nodeUuid", "n1")
                .param("pathPrefix", "/Sites")
                .param("fromMs", "1000")
                .param("toMs", "2000")
                .param("unfiltered", "true")
                .param("rows", "7")
                .param("start", "3"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditQueryService).search(captor.capture());
        AuditQuery query = captor.getValue();

        assertEquals("abeecher", query.principal());
        assertEquals("content-read", query.eventType());
        assertEquals("n1", query.nodeUuid());
        assertEquals("/Sites", query.pathPrefix());
        assertEquals(Long.valueOf(1000L), query.fromMs());
        assertEquals(Long.valueOf(2000L), query.toMs());
        assertTrue(query.unfiltered());
        assertEquals(7, query.rows());
        assertEquals(3, query.start());
    }

    @Test
    public void readsAnUnfilteredPageCarryingOnlyItsOwnParameters() throws Exception
    {
        when(auditQueryService.search(any())).thenReturn(new AuditPage(List.of(), 0L, null));

        mockMvc.perform(get("/api/v1/audit/events")
                .param("unfiltered", "true")
                .param("rows", "1"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditQueryService).search(captor.capture());

        assertTrue(captor.getValue()
                .unfiltered());
        assertEquals(1, captor.getValue()
                .rows());
    }

    @Test
    public void bindsEveryFacetQueryParameterByItsPublishedName() throws Exception
    {
        when(auditQueryService.facet(any(), anyString(), anyInt(), isNull(), anyString()))
                .thenReturn(Map.of("abeecher", 3L));

        mockMvc.perform(get("/api/v1/audit/facets")
                .param("field", "PRINCIPAL")
                .param("unfiltered", "true")
                .param("limit", "25")
                .param("contains", "bee"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> field = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> contains = ArgumentCaptor.forClass(String.class);
        verify(auditQueryService).facet(any(), field.capture(), limit.capture(), isNull(), contains.capture());

        assertEquals("PRINCIPAL", field.getValue());
        assertEquals(Integer.valueOf(25), limit.getValue());
        assertEquals("bee", contains.getValue());
    }
}
