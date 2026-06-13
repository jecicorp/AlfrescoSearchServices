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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.apache.solr.common.SolrDocument;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class EmptyNodeStrategyTest
{
    private static final long DBID = 42L;

    @Mock private SOLRAPIClient solrApiClient;
    @Mock private InformationServer infoSrv;

    private EmptyNodeStrategy strategy;
    private SolrDocument doc;
    private TenantDbId docRef;

    @Before
    public void setUp()
    {
        strategy = new EmptyNodeStrategy(solrApiClient, infoSrv);
        doc = new SolrDocument();
        docRef = new TenantDbId();
        docRef.dbId = DBID;
        docRef.tenant = "";
    }

    @Test
    public void categoryReturnsEmptyNode()
    {
        assertEquals("EMPTY_NODE", strategy.category());
    }

    @Test
    public void tryRepairReturnsSuccessWhenNodeNotFound() throws Exception
    {
        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(Collections.emptyList());

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(DBID, result.dbId());
        assertEquals("EMPTY_NODE", result.category());
        assertTrue(result.message().contains("not found"));
        verify(infoSrv).deleteByNodeId(DBID);
    }

    @Test
    public void tryRepairReturnsSuccessWhenPropertiesEmpty() throws Exception
    {
        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(Collections.emptyMap());

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(DBID, result.dbId());
        assertTrue(result.message().contains("legitimately empty"));
        verify(infoSrv, never()).deleteByNodeId(anyLong());
    }

    @Test
    public void tryRepairReturnsSuccessWhenPropertiesNull() throws Exception
    {
        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(null);

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(DBID, result.dbId());
        assertTrue(result.message().contains("legitimately empty"));
        verify(infoSrv, never()).deleteByNodeId(anyLong());
    }

    @Test
    public void tryRepairReturnsNullWhenNodeHasProperties() throws Exception
    {
        Map<String, PropertyValue> props = new HashMap<>();
        props.put("some_property", mock(PropertyValue.class));

        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn((Map) props);

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNull(result);
        verify(infoSrv, never()).deleteByNodeId(anyLong());
    }

    @Test
    public void tryRepairReturnsFailureOnException() throws Exception
    {
        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenThrow(new RuntimeException("Test error"));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertFalse(result.success());
        assertEquals(DBID, result.dbId());
        assertTrue(result.message().contains("Error"));
        verify(infoSrv, never()).deleteByNodeId(anyLong());
    }
}
