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

package org.alfresco.indexing.server.solrj;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.*;
import static org.alfresco.indexing.server.solrj.SolrJQueryService.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.solr.AclReport;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.Transaction;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class SolrJQueryServiceTest
{
    private SolrClient solrClient;
    private SolrJQueryService queryService;
    private static final String COLLECTION = "alfresco";

    @Before
    public void setUp()
    {
        solrClient = mock(SolrClient.class);
        queryService = new SolrJQueryService(solrClient, COLLECTION);
    }

    // -------------------------------------------------------------------------
    // getTrackerInitialState
    // -------------------------------------------------------------------------

    @Test
    public void getTrackerInitialState_withBothStateDocs_populatesState() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(2);

        SolrDocument aclTxDoc = new SolrDocument();
        aclTxDoc.addField(FIELD_S_ACLTXCOMMITTIME, 5000L);
        aclTxDoc.addField(FIELD_S_ACLTXID, 50L);
        docs.add(aclTxDoc);

        SolrDocument txDoc = new SolrDocument();
        txDoc.addField(FIELD_S_TXCOMMITTIME, 3000L);
        txDoc.addField(FIELD_S_TXID, 30L);
        docs.add(txDoc);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        TrackerState state = queryService.getTrackerInitialState(1000, 3600000);

        assertEquals(5000L, state.getLastIndexedChangeSetCommitTime());
        assertEquals(50L, state.getLastIndexedChangeSetId());
        assertEquals(3000L, state.getLastIndexedTxCommitTime());
        assertEquals(30L, state.getLastIndexedTxId());
        assertTrue(state.getLastStartTime() > 0);
    }

    @Test
    public void getTrackerInitialState_emptyIndex_returnsDefaultState() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        TrackerState state = queryService.getTrackerInitialState(1000, 3600000);

        assertEquals(0L, state.getLastIndexedChangeSetCommitTime());
        assertEquals(0L, state.getLastIndexedChangeSetId());
        assertEquals(0L, state.getLastIndexedTxCommitTime());
        assertEquals(0L, state.getLastIndexedTxId());
    }

    // -------------------------------------------------------------------------
    // continueState
    // -------------------------------------------------------------------------

    @Test
    public void continueState_updatesTimeWindows()
    {
        TrackerState state = new TrackerState();
        state.setLastIndexedTxCommitTime(1000000L);
        state.setLastIndexedChangeSetCommitTime(2000000L);
        state.setLastStartTime(System.currentTimeMillis() - 5000);

        queryService.continueState(state, 1000, 3600000);

        assertTrue(state.getTimeToStopIndexing() > 0);
        assertTrue(state.getTimeBeforeWhichThereCanBeNoHoles() > 0);
        assertTrue(state.getLastStartTime() > 0);
    }

    // -------------------------------------------------------------------------
    // txnInIndex / aclChangeSetInIndex
    // -------------------------------------------------------------------------

    @Test
    public void txnInIndex_found_returnsTrue() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertTrue(queryService.txnInIndex(42L, true));
    }

    @Test
    public void txnInIndex_notFound_returnsFalse() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertFalse(queryService.txnInIndex(99L, false));
    }

    @Test
    public void txnInIndex_cached_doesNotQuerySolr() throws Exception
    {
        // First call: populate cache
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        queryService.txnInIndex(42L, true);

        // Second call: should use cache, not query Solr again
        assertTrue(queryService.txnInIndex(42L, true));
        verify(solrClient, times(1)).query(eq(COLLECTION), any(SolrQuery.class));
    }

    @Test
    public void txnInIndex_notFoundWithPopulateCache_doesNotPoisonCache() throws Exception
    {
        // First call: txn NOT in index, but populateCache=true
        SolrDocumentList emptyDocs = new SolrDocumentList();
        emptyDocs.setNumFound(0);
        QueryResponse emptyResponse = mockQueryResponse(emptyDocs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(emptyResponse);

        assertFalse("Should return false when txn is not in index",
                queryService.txnInIndex(77L, true));

        // Second call: same txnId — must query Solr again (not return true from cache)
        assertFalse("Should still return false — cache must NOT contain not-found entries",
                queryService.txnInIndex(77L, true));

        // Solr should have been queried twice (cache must NOT short-circuit)
        verify(solrClient, times(2)).query(eq(COLLECTION), any(SolrQuery.class));
    }

    @Test
    public void aclChangeSetInIndex_found_returnsTrue() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertTrue(queryService.aclChangeSetInIndex(10L, true));
    }

    // -------------------------------------------------------------------------
    // isInIndex(String)
    // -------------------------------------------------------------------------

    @Test
    public void isInIndex_found_returnsTrue() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertTrue(queryService.isInIndex("SOME_ID"));
    }

    @Test
    public void isInIndex_cached_doesNotQueryAgain() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        queryService.isInIndex("SOME_ID");
        queryService.isInIndex("SOME_ID");
        verify(solrClient, times(1)).query(eq(COLLECTION), any(SolrQuery.class));
    }

    // -------------------------------------------------------------------------
    // nodeCount
    // -------------------------------------------------------------------------

    @Test
    public void nodeCount_returnsCorrectCount() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(42);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(42L, queryService.nodeCount());
    }

    // -------------------------------------------------------------------------
    // maxNodeId / minNodeId
    // -------------------------------------------------------------------------

    @Test
    public void maxNodeId_returnsHighestDbid() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        SolrDocument doc = new SolrDocument();
        doc.addField(FIELD_DBID, 999L);
        docs.add(doc);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(999L, queryService.maxNodeId());

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(eq(COLLECTION), captor.capture());
        SolrQuery query = captor.getValue();
        assertEquals("1", query.get("rows"));
        assertTrue(query.getSortField().contains(FIELD_DBID));
    }

    @Test
    public void minNodeId_returnsLowestDbid() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        SolrDocument doc = new SolrDocument();
        doc.addField(FIELD_DBID, 1L);
        docs.add(doc);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(1L, queryService.minNodeId());
    }

    @Test
    public void maxNodeId_emptyIndex_returnsZero() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(0L, queryService.maxNodeId());
    }

    // -------------------------------------------------------------------------
    // getTxDocsSize / getAclTxDocsSize
    // -------------------------------------------------------------------------

    @Test
    public void getTxDocsSize_returnsCorrectCount() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(5);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(5, queryService.getTxDocsSize("100", "1234567890"));
    }

    @Test
    public void getAclTxDocsSize_returnsCorrectCount() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(3);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(3, queryService.getAclTxDocsSize("10", "9876543210"));
    }

    // -------------------------------------------------------------------------
    // getMaxTransactionIdAndCommitTimeInIndex
    // -------------------------------------------------------------------------

    @Test
    public void getMaxTransactionIdAndCommitTimeInIndex_withState_returnsValues() throws Exception
    {
        // Mock the standard query response for STATE_DOC_TX
        SolrDocument stateDoc = new SolrDocument();
        stateDoc.addField(FIELD_S_TXID, 100L);
        stateDoc.addField(FIELD_S_TXCOMMITTIME, 9999L);

        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList docList = new SolrDocumentList();
        docList.add(stateDoc);
        docList.setNumFound(1);
        when(response.getResults()).thenReturn(docList);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Transaction txn = queryService.getMaxTransactionIdAndCommitTimeInIndex();
        assertEquals(100L, txn.getId());
        assertEquals(9999L, txn.getCommitTimeMs());
    }

    @Test
    public void getMaxTransactionIdAndCommitTimeInIndex_noState_returnsDefaults() throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList emptyList = new SolrDocumentList();
        emptyList.setNumFound(0);
        when(response.getResults()).thenReturn(emptyList);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Transaction txn = queryService.getMaxTransactionIdAndCommitTimeInIndex();
        assertEquals(0L, txn.getId());
        assertEquals(0L, txn.getCommitTimeMs());
    }

    // -------------------------------------------------------------------------
    // getMaxAclChangeSetIdAndCommitTimeInIndex
    // -------------------------------------------------------------------------

    @Test
    public void getMaxAclChangeSetIdAndCommitTimeInIndex_withState_returnsValues() throws Exception
    {
        SolrDocument stateDoc = new SolrDocument();
        stateDoc.addField(FIELD_S_ACLTXID, 200L);
        stateDoc.addField(FIELD_S_ACLTXCOMMITTIME, 8888L);

        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList docList = new SolrDocumentList();
        docList.add(stateDoc);
        docList.setNumFound(1);
        when(response.getResults()).thenReturn(docList);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        AclChangeSet cs = queryService.getMaxAclChangeSetIdAndCommitTimeInIndex();
        assertEquals(200L, cs.getId());
        assertEquals(8888L, cs.getCommitTimeMs());
    }

    // -------------------------------------------------------------------------
    // getIndexCap
    // -------------------------------------------------------------------------

    @Test
    public void getIndexCap_withCapDoc_returnsAbsoluteDbid() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        SolrDocument doc = new SolrDocument();
        doc.addField(FIELD_DBID, -500L);
        docs.add(doc);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(500L, queryService.getIndexCap());
    }

    @Test
    public void getIndexCap_noCapDoc_returnsNegativeOne() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        assertEquals(-1L, queryService.getIndexCap());
    }

    // -------------------------------------------------------------------------
    // getErrorDocIds
    // -------------------------------------------------------------------------

    @Test
    public void getErrorDocIds_returnsNodeIds() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(2);

        SolrDocument err1 = new SolrDocument();
        err1.addField(FIELD_SOLR4_ID, "ERROR-100");
        docs.add(err1);

        SolrDocument err2 = new SolrDocument();
        err2.addField(FIELD_SOLR4_ID, "ERROR-200");
        docs.add(err2);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Set<Long> errorIds = queryService.getErrorDocIds();
        assertEquals(2, errorIds.size());
        assertTrue(errorIds.contains(100L));
        assertTrue(errorIds.contains(200L));
    }

    // -------------------------------------------------------------------------
    // checkAclInIndex
    // -------------------------------------------------------------------------

    @Test
    public void checkAclInIndex_setsCount() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(3);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        AclReport report = new AclReport();
        queryService.checkAclInIndex(42L, report);
        assertEquals(Long.valueOf(3L), report.getIndexedAclDocCount());
    }

    // -------------------------------------------------------------------------
    // addCommonNodeReportInfo
    // -------------------------------------------------------------------------

    @Test
    public void addCommonNodeReportInfo_setsCount() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        NodeReport report = new NodeReport();
        report.setDbid(500L);
        queryService.addCommonNodeReportInfo(report);
        assertEquals(Long.valueOf(1L), report.getIndexedNodeDocCount());
    }

    // -------------------------------------------------------------------------
    // getCoreStats
    // -------------------------------------------------------------------------

    @Test
    public void getCoreStats_returnsFacetCounts() throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);
        when(response.getResults()).thenReturn(docs);

        FacetField ff = new FacetField(FIELD_DOC_TYPE);
        ff.add(DOC_TYPE_NODE, 100);
        ff.add(DOC_TYPE_TX, 50);
        ff.add(DOC_TYPE_ACL, 25);
        ff.add(DOC_TYPE_ACL_TX, 10);
        when(response.getFacetField(FIELD_DOC_TYPE)).thenReturn(ff);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Iterable<Map.Entry<String, Object>> stats = queryService.getCoreStats();
        boolean foundNodes = false;
        for (Map.Entry<String, Object> entry : stats)
        {
            if ("Alfresco Nodes in Index".equals(entry.getKey()))
            {
                assertEquals(100L, entry.getValue());
                foundNodes = true;
            }
        }
        assertTrue("Should have found 'Alfresco Nodes in Index' stat", foundNodes);
    }

    // -------------------------------------------------------------------------
    // clearProcessedTransactions / clearProcessedAclChangeSets
    // -------------------------------------------------------------------------

    @Test
    public void clearProcessedTransactions_clearsCacheAndAllowsRequery() throws Exception
    {
        // Populate the cache
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        queryService.txnInIndex(42L, true);
        // Cache should contain 42

        queryService.clearProcessedTransactions();

        // After clear, should query again
        queryService.txnInIndex(42L, false);
        verify(solrClient, times(2)).query(eq(COLLECTION), any(SolrQuery.class));
    }

    @Test
    public void clearProcessedAclChangeSets_clearsCacheAndAllowsRequery() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        queryService.aclChangeSetInIndex(10L, true);

        queryService.clearProcessedAclChangeSets();

        queryService.aclChangeSetInIndex(10L, false);
        verify(solrClient, times(2)).query(eq(COLLECTION), any(SolrQuery.class));
    }

    // -------------------------------------------------------------------------
    // getCascades
    // -------------------------------------------------------------------------

    @Test
    public void getCascades_returnsTransactions() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(2);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField(FIELD_S_TXID, 10L);
        doc1.addField(FIELD_S_TXCOMMITTIME, 1000L);
        docs.add(doc1);

        SolrDocument doc2 = new SolrDocument();
        doc2.addField(FIELD_S_TXID, 20L);
        doc2.addField(FIELD_S_TXCOMMITTIME, 2000L);
        docs.add(doc2);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        List<Transaction> cascades = queryService.getCascades(10);
        assertEquals(2, cascades.size());
        assertEquals(10L, cascades.get(0).getId());
        assertEquals(1000L, cascades.get(0).getCommitTimeMs());
        assertEquals(20L, cascades.get(1).getId());
    }

    // -------------------------------------------------------------------------
    // getDescendantNodeIds
    // -------------------------------------------------------------------------

    @Test
    public void getDescendantNodeIds_returnsDbidToTxnIdMap() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(2);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField(FIELD_DBID, 100L);
        doc1.addField(FIELD_TXID, 5L);
        docs.add(doc1);

        SolrDocument doc2 = new SolrDocument();
        doc2.addField(FIELD_DBID, 200L);
        doc2.addField(FIELD_TXID, 8L);
        docs.add(doc2);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Map<Long, Long> result = queryService.getDescendantNodeIds("workspace://SpacesStore/parent-uuid");
        assertEquals(2, result.size());
        assertEquals(Long.valueOf(5L), result.get(100L));
        assertEquals(Long.valueOf(8L), result.get(200L));

        // Verify the query format: ANCESTOR:<nodeRef> AND DOC_TYPE:Node
        ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(eq(COLLECTION), queryCaptor.capture());
        String q = queryCaptor.getValue().get("q");
        assertTrue(q.contains("ANCESTOR:\"workspace://SpacesStore/parent-uuid\""));
        assertTrue(q.contains("DOC_TYPE:Node"));
    }

    @Test
    public void getDescendantNodeIds_emptyResults_returnsEmptyMap() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(0);

        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        Map<Long, Long> result = queryService.getDescendantNodeIds("workspace://SpacesStore/some-uuid");
        assertTrue(result.isEmpty());
    }

    @Test
    public void getDescendantNodeIds_nullOrEmpty_returnsEmptyMap() throws Exception
    {
        assertTrue(queryService.getDescendantNodeIds(null).isEmpty());
        assertTrue(queryService.getDescendantNodeIds("").isEmpty());
        // No Solr query should have been made
        verify(solrClient, never()).query(eq(COLLECTION), any(SolrQuery.class));
    }

    // -------------------------------------------------------------------------
    // getFieldValueLong
    // -------------------------------------------------------------------------

    @Test
    public void getFieldValueLong_withNumber()
    {
        SolrDocument doc = new SolrDocument();
        doc.addField("field", 42L);
        assertEquals(42L, SolrJQueryService.getFieldValueLong(doc, "field"));
    }

    @Test
    public void getFieldValueLong_withString()
    {
        SolrDocument doc = new SolrDocument();
        doc.addField("field", "123");
        assertEquals(123L, SolrJQueryService.getFieldValueLong(doc, "field"));
    }

    @Test
    public void getFieldValueLong_withNull_returnsZero()
    {
        SolrDocument doc = new SolrDocument();
        assertEquals(0L, SolrJQueryService.getFieldValueLong(doc, "missing"));
    }

    // -------------------------------------------------------------------------
    // getCascadeNodeIds
    // -------------------------------------------------------------------------

    /**
     * The repository marks structurally-changed nodes (rename/move) with the
     * residual property {@code sys:cascadeTx} = txnId. The cascade lookup must
     * query that property's Solr field ({@code long@s_@{sys}cascadeTx}) — NOT
     * {@code int@s_@cascade}, which is the 0/1 cascade flag on Tx documents.
     */
    @Test
    public void getCascadeNodeIds_queriesSysCascadeTxProperty() throws Exception
    {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(1);
        SolrDocument doc = new SolrDocument();
        doc.addField(FIELD_DBID, 42L);
        docs.add(doc);
        QueryResponse response = mockQueryResponse(docs);
        when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

        java.util.Set<Long> nodeIds = queryService.getCascadeNodeIds(java.util.Arrays.asList(101L, 102L));

        assertEquals(java.util.Collections.singleton(42L), nodeIds);

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(eq(COLLECTION), captor.capture());
        String q = captor.getValue().getQuery();
        assertTrue("query must target the sys:cascadeTx property field, was: " + q,
                q.contains("cascadeTx"));
        assertTrue("query must include the txn ids, was: " + q,
                q.contains("101") && q.contains("102"));
        assertFalse("query must not use the Tx-document cascade flag field, was: " + q,
                q.contains(FIELD_CASCADE_FLAG));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private QueryResponse mockQueryResponse(SolrDocumentList docs) throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        return response;
    }
}
