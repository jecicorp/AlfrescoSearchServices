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

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DBID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DOC_TYPE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_HAS_INDEXING_ERROR;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_INTXID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class SolrIndexProbeTest
{
    private final SolrClient solrClient = mock(SolrClient.class);
    private final SolrIndexProbe probe = new SolrIndexProbe(solrClient);

    @Test
    public void theAwaitedNodesAreQueriedFiveHundredAtATime() throws Exception
    {
        answer(new SolrDocumentList());

        probe.find("alfresco", LongStream.rangeClosed(1L, 1200L).boxed().toList());

        ArgumentCaptor<SolrQuery> queries = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient, times(3)).query(eq("alfresco"), queries.capture());
        SolrQuery first = queries.getAllValues().get(0);
        assertTrue(first.getQuery(), first.getQuery().startsWith("DBID:(1 OR 2 OR "));
        assertTrue(first.getQuery(),
                first.getQuery().endsWith(" OR 500) AND DOC_TYPE:(Node OR ErrorNode OR UnindexedNode)"));
        assertEquals("lucene", first.get("defType"));
        assertEquals("/query", first.get("qt"));
        assertEquals(Integer.valueOf(1000), first.getRows());
        assertEquals("DBID,DOC_TYPE,INTXID", first.getFields());
        SolrQuery last = queries.getAllValues().get(2);
        assertTrue(last.getQuery(), last.getQuery().startsWith("DBID:(1001 OR "));
        assertEquals(Integer.valueOf(400), last.getRows());
    }

    @Test
    public void theDocumentsAreGroupedByDbid() throws Exception
    {
        SolrDocumentList documents = new SolrDocumentList();
        documents.add(document(1L, "Node", 10L, null));
        documents.add(document(1L, "ErrorNode", 11L, null));
        documents.add(document(2L, "Node", 5L, null));
        answer(documents);

        Map<Long, List<IndexedDocument>> found = probe.find("alfresco", List.of(1L, 2L, 3L));

        assertEquals(List.of(new IndexedDocument(1L, "Node", 10L),
                new IndexedDocument(1L, "ErrorNode", 11L)), found.get(1L));
        assertEquals(List.of(new IndexedDocument(2L, "Node", 5L)), found.get(2L));
        assertEquals(null, found.get(3L));
    }

    @Test
    public void aNodeWithAnIndexingErrorIsReadAsAPlainNode() throws Exception
    {
        SolrDocumentList documents = new SolrDocumentList();
        documents.add(document(2L, "Node", 5L, "true"));
        answer(documents);

        List<IndexedDocument> found = probe.find("alfresco", List.of(2L)).get(2L);

        assertEquals(List.of(new IndexedDocument(2L, "Node", 5L)), found);
        assertEquals(Optional.of(new AwaitEvent.Searchable(2L)), Readiness.decide(2L, 5L, found));
    }

    @Test
    public void aSolrFailureIsAnIOException() throws Exception
    {
        when(solrClient.query(eq("alfresco"), any(SolrQuery.class))).thenThrow(new SolrServerException("down"));

        assertThrows(IOException.class, () -> probe.find("alfresco", List.of(1L)));
    }

    @Test
    public void aRejectedQueryIsAnIOException() throws Exception
    {
        when(solrClient.query(eq("alfresco"), any(SolrQuery.class)))
                .thenThrow(new IllegalStateException("undefined field DBID"));

        assertThrows(IOException.class, () -> probe.find("alfresco", List.of(1L)));
    }

    @Test
    public void noDbidMeansNoQuery() throws Exception
    {
        assertEquals(Map.of(), probe.find("alfresco", List.of()));
        verifyNoInteractions(solrClient);
    }

    private void answer(SolrDocumentList documents) throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(documents);
        when(solrClient.query(eq("alfresco"), any(SolrQuery.class))).thenReturn(response);
    }

    private static SolrDocument document(long dbid, String docType, long txId, String indexingError)
    {
        SolrDocument document = new SolrDocument();
        document.setField(FIELD_DBID, dbid);
        document.setField(FIELD_DOC_TYPE, docType);
        document.setField(FIELD_INTXID, txId);
        if (indexingError != null)
        {
            document.setField(FIELD_HAS_INDEXING_ERROR, indexingError);
        }
        return document;
    }
}
