/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.server.solrj.LocalDictionaryService;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.apache.solr.common.SolrDocument;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
public class UnresolvedModelStrategyTest
{
    private static final long DBID = 42L;
    private static final long TXN_ID = 100L;

    @Mock private SOLRAPIClient solrApiClient;
    @Mock private InformationServer infoSrv;
    @Mock private LocalDictionaryService dictionaryService;

    private UnresolvedModelStrategy strategy;
    private SolrDocument doc;
    private TenantDbId docRef;

    @Before
    public void setUp()
    {
        strategy = new UnresolvedModelStrategy(solrApiClient, infoSrv, dictionaryService);
        doc = new SolrDocument();
        docRef = new TenantDbId();
        docRef.dbId = DBID;
        docRef.tenant = "";
    }

    @Test
    public void categoryReturnsUnresolvedModel()
    {
        assertEquals("UNRESOLVED_MODEL", strategy.category());
    }

    @Test
    public void tryRepairReturnsNullWhenNoMetadata() throws Exception
    {
        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(Collections.emptyList());

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNull(result);
    }

    @Test
    public void tryRepairReturnsSuccessWhenAllPropertiesResolved() throws Exception
    {
        QName resolvedProp = QName.createQName("http://www.alfresco.org/model/content/1.0", "name");

        Map<QName, PropertyValue> props = new HashMap<>();
        props.put(resolvedProp, mock(PropertyValue.class));

        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(props);
        when(nmd.getId()).thenReturn(DBID);
        when(nmd.getTxnId()).thenReturn(TXN_ID);

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));
        when(dictionaryService.getPropertyDefinition(resolvedProp))
                .thenReturn(mock(PropertyDefinition.class));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(DBID, result.dbId());
        assertEquals("UNRESOLVED_MODEL", result.category());
        verify(infoSrv).indexNode(any(), eq(true));
    }

    @Test
    public void tryRepairReturnsFailureWhenModelStillMissing() throws Exception
    {
        QName resolvedProp = QName.createQName("http://www.alfresco.org/model/content/1.0", "name");
        QName unresolvedProp = QName.createQName("http://www.example.org/model/music/1.0", "lyricist");

        Map<QName, PropertyValue> props = new HashMap<>();
        props.put(resolvedProp, mock(PropertyValue.class));
        props.put(unresolvedProp, mock(PropertyValue.class));

        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(props);

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));
        when(dictionaryService.getPropertyDefinition(resolvedProp))
                .thenReturn(mock(PropertyDefinition.class));
        when(dictionaryService.getPropertyDefinition(unresolvedProp))
                .thenReturn(null);

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNotNull(result);
        assertFalse(result.success());
        assertEquals(DBID, result.dbId());
        assertTrue(result.message().contains("http://www.example.org/model/music/1.0"));
        verify(infoSrv, never()).indexNode(any(), anyBoolean());
    }

    @Test
    public void tryRepairReturnsNullWhenPropertiesEmpty() throws Exception
    {
        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(Collections.emptyMap());

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNull(result);
    }

    @Test
    public void tryRepairReturnsNullWhenPropertiesNull() throws Exception
    {
        NodeMetaData nmd = mock(NodeMetaData.class);
        when(nmd.getProperties()).thenReturn(null);

        when(solrApiClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(List.of(nmd));

        RepairResult result = strategy.tryRepair(doc, docRef);

        assertNull(result);
    }
}
