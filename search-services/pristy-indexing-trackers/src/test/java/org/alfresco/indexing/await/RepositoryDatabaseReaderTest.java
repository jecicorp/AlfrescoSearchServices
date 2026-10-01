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

package org.alfresco.indexing.await;

import static org.alfresco.indexing.await.DatabaseNode.Status.LIVE;
import static org.alfresco.indexing.await.DatabaseNode.Status.ORPHAN;
import static org.alfresco.indexing.await.DatabaseNode.Status.UNREACHABLE;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.alfresco.solr.client.GetNodesParameters;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.SOLRAPIClient;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class RepositoryDatabaseReaderTest
{
    private static final String WORKSPACE = "workspace://SpacesStore";
    private static final String ARCHIVE = "archive://SpacesStore";

    private final SOLRAPIClient client = mock(SOLRAPIClient.class);
    private final RepositoryDatabaseReader reader = new RepositoryDatabaseReader(client, WORKSPACE);

    @Test
    public void closeNodesAreReadInOneRangeRequest() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt())).thenReturn(List.of(
                node(100L, 7L, SolrApiNodeStatus.UPDATED, WORKSPACE),
                node(105L, 8L, SolrApiNodeStatus.UPDATED, WORKSPACE)));

        Map<Long, DatabaseNode> read = reader.read(List.of(105L, 100L, 101L));

        ArgumentCaptor<GetNodesParameters> parameters = ArgumentCaptor.forClass(GetNodesParameters.class);
        verify(client).getNodes(parameters.capture(), eq(6));
        assertEquals(Long.valueOf(100L), parameters.getValue().getFromNodeId());
        assertEquals(Long.valueOf(105L), parameters.getValue().getToNodeId());
        assertEquals(new DatabaseNode(100L, LIVE, 7L), read.get(100L));
        assertEquals(new DatabaseNode(101L, ORPHAN, -1L), read.get(101L));
        assertEquals(new DatabaseNode(105L, LIVE, 8L), read.get(105L));
    }

    @Test
    public void distantNodesAreReadInSeparateRequests() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt())).thenReturn(List.of());

        reader.read(List.of(1L, 5000L));

        verify(client, times(2)).getNodes(any(GetNodesParameters.class), eq(1));
    }

    @Test
    public void aRangeNeverSpansMoreThanTheLimit() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt())).thenReturn(List.of());

        reader.read(List.of(1L, 2000L, 2001L));

        ArgumentCaptor<GetNodesParameters> wide = ArgumentCaptor.forClass(GetNodesParameters.class);
        verify(client).getNodes(wide.capture(), eq(2000));
        assertEquals(Long.valueOf(1L), wide.getValue().getFromNodeId());
        assertEquals(Long.valueOf(2000L), wide.getValue().getToNodeId());
        ArgumentCaptor<GetNodesParameters> single = ArgumentCaptor.forClass(GetNodesParameters.class);
        verify(client).getNodes(single.capture(), eq(1));
        assertEquals(Long.valueOf(2001L), single.getValue().getFromNodeId());
    }

    @Test
    public void aDeletedNodeIsAnOrphan() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt()))
                .thenReturn(List.of(node(100L, 7L, SolrApiNodeStatus.DELETED, WORKSPACE)));

        assertEquals(new DatabaseNode(100L, ORPHAN, -1L), reader.read(List.of(100L)).get(100L));
    }

    @Test
    public void aNodeOfAnotherStoreIsAnOrphan() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt()))
                .thenReturn(List.of(node(100L, 7L, SolrApiNodeStatus.UPDATED, ARCHIVE)));

        assertEquals(new DatabaseNode(100L, ORPHAN, -1L), reader.read(List.of(100L)).get(100L));
    }

    @Test
    public void anUnreachableRepositoryMarksTheWholeRange() throws Exception
    {
        when(client.getNodes(any(GetNodesParameters.class), anyInt())).thenThrow(new IOException("Connection refused"));

        Map<Long, DatabaseNode> read = reader.read(List.of(100L, 101L));

        assertEquals(new DatabaseNode(100L, UNREACHABLE, -1L), read.get(100L));
        assertEquals(new DatabaseNode(101L, UNREACHABLE, -1L), read.get(101L));
    }

    private static Node node(long dbid, long tx, SolrApiNodeStatus status, String store)
    {
        Node node = new Node();
        node.setId(dbid);
        node.setTxnId(tx);
        node.setStatus(status);
        node.setNodeRef(store + "/0f2c7a4e-5b1d-4c3e-9a8f-" + String.format("%012d", dbid));
        return node;
    }
}
