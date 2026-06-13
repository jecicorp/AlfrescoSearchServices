/*
 * Copyright 2026 - Jeci SARL - https://jeci.fr
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * http://www.gnu.org/licenses/.
 */
package org.alfresco.indexing.server.solrj;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.SOLRAPIClient;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class SolrJInformationServerCascadeTest
{
    /**
     * The repository's {@code api/solr/metadata} webscript returns an empty node
     * list for a fromNodeId/toNodeId range when {@code maxResults} is absent.
     * Upstream {@code SolrInformationServer.getCascadeNodes} always set
     * {@code maxResults=1}; omitting it silently breaks cascade re-indexing.
     */
    @Test
    public void getCascadeNodesSendsMaxResultsOne() throws Exception
    {
        // Solr returns one cascade-flagged node id (DBID 1939)
        SolrClient solrClient = mock(SolrClient.class);
        SolrDocumentList docs = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.addField("DBID", 1939L);
        docs.add(doc);
        docs.setNumFound(1);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(anyString(), any(SolrQuery.class))).thenReturn(response);

        SOLRAPIClient repositoryClient = mock(SOLRAPIClient.class);
        when(repositoryClient.getNodesMetaData(any(NodeMetaDataParameters.class)))
                .thenReturn(Collections.singletonList(new NodeMetaData()));

        SolrJInformationServer server = new SolrJInformationServer(
                solrClient, "alfresco", new Properties(), null, repositoryClient, null);

        List<NodeMetaData> result = server.getCascadeNodes(Collections.singletonList(99L));

        assertEquals(1, result.size());

        ArgumentCaptor<NodeMetaDataParameters> captor = ArgumentCaptor.forClass(NodeMetaDataParameters.class);
        verify(repositoryClient).getNodesMetaData(captor.capture());
        NodeMetaDataParameters nmdp = captor.getValue();
        assertEquals(Long.valueOf(1939L), nmdp.getFromNodeId());
        assertEquals(Long.valueOf(1939L), nmdp.getToNodeId());
        assertTrue("maxResults must be set — the repo metadata webscript returns "
                        + "an empty list for node ranges without it",
                nmdp.getMaxResults().isPresent());
        assertEquals(1, nmdp.getMaxResults().getAsInt());
    }
}
