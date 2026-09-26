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
package org.alfresco.indexing.diagnostic;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alfresco.indexing.server.solrj.JavaBitSetAdapter;
import org.alfresco.indexing.server.solrj.SolrDocumentMapper;
import org.alfresco.indexing.server.solrj.SolrJQueryService;
import org.alfresco.solr.InformationServerCollectionProvider;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;

public class DiagnosticDocumentNeutralityTest
{
    private static final String CORE = "alfresco";
    private static final Pattern RANGE = Pattern.compile("(\\w+):\\[(\\d+) TO (\\d+)\\]");
    private static final Pattern TERM = Pattern.compile("(\\w+):(\\w+)");

    private final List<SolrInputDocument> documents = new ArrayList<>();
    private final SolrClient solrClient = mock(SolrClient.class);
    private final SolrJQueryService queryService = new SolrJQueryService(solrClient, CORE);
    private final DiagnosticStore store = new DiagnosticStore(solrClient, JsonMapper.builder().findAndAddModules().build());
    private final InformationServerCollectionProvider provider = mock(InformationServerCollectionProvider.class);

    @Before
    public void setUp() throws Exception
    {
        when(provider.getOpenBitSetInstance()).thenAnswer(invocation -> new JavaBitSetAdapter());
        when(solrClient.add(eq(CORE), any(SolrInputDocument.class))).thenAnswer(invocation -> {
            SolrInputDocument added = invocation.getArgument(1);
            documents.removeIf(existing -> existing.getFieldValue("id").equals(added.getFieldValue("id")));
            documents.add(added);
            return new UpdateResponse();
        });
        when(solrClient.query(eq(CORE), any(SolrQuery.class))).thenAnswer(invocation -> answer(invocation.getArgument(1)));

        SolrDocumentMapper mapper = new SolrDocumentMapper();
        for (long id = 1; id <= 3; id++)
        {
            Transaction transaction = new Transaction();
            transaction.setId(id);
            transaction.setCommitTimeMs(id * 1000);
            documents.add(mapper.toTransactionDoc(transaction));
            documents.add(node(10 + id));
        }
        documents.add(mapper.toAclChangeSetDoc(new AclChangeSet(1, 1000, 1)));
        documents.add(mapper.toAclChangeSetDoc(new AclChangeSet(2, 2000, 1)));
        documents.add(mapper.toTrackerStateDoc(3, 3000));
        documents.add(mapper.toErrorNodeDoc(20, 3L, new IllegalStateException("unresolved model")));
    }

    @Test
    public void aDiagnosticStoredTwiceLeavesEveryReportedFigureUnchanged() throws Exception
    {
        Map<String, Object> before = figures();

        store.save(CORE, stored("2026-09-25T15:04:40Z"));
        Map<String, Object> afterFirst = figures();
        store.save(CORE, stored("2026-09-25T16:00:00Z"));
        Map<String, Object> afterSecond = figures();

        assertEquals(3L, before.get("Alfresco Transactions in Index"));
        assertEquals(3L, before.get("Alfresco Nodes in Index"));
        assertEquals(1L, before.get("Alfresco Error Nodes in Index"));
        assertEquals(3L, before.get("uniqueTransactionDocs"));
        assertEquals(0L, before.get("missingTx"));
        assertEquals(before, afterFirst);
        assertEquals(before, afterSecond);
    }

    private Map<String, Object> figures() throws IOException
    {
        IOpenBitSet txIds = new JavaBitSetAdapter();
        for (long id = 1; id <= 3; id++)
        {
            txIds.set(id);
        }
        IOpenBitSet aclTxIds = new JavaBitSetAdapter();
        aclTxIds.set(1);
        aclTxIds.set(2);
        IndexHealthReport tx = queryService.reportIndexTransactions(1L, txIds, 3L, provider, ProgressListener.NONE);
        IndexHealthReport acl = queryService.reportAclTransactionsInIndex(1L, aclTxIds, 2L, provider, ProgressListener.NONE);

        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("transactionDocs", tx.getTransactionDocsInIndex());
        figures.put("uniqueTransactionDocs", tx.getUniqueTransactionDocsInIndex());
        figures.put("leafDocs", tx.getLeafDocCountInIndex());
        figures.put("errorDocs", tx.getErrorDocCountInIndex());
        figures.put("unindexedDocs", tx.getUnindexedDocCountInIndex());
        figures.put("missingTx", tx.getMissingTxFromIndex().cardinality());
        figures.put("duplicatedTx", tx.getDuplicatedTxInIndex().cardinality());
        figures.put("txNotInDb", tx.getTxInIndexButNotInDb().cardinality());
        figures.put("duplicatedLeaf", tx.getDuplicatedLeafInIndex().cardinality());
        figures.put("aclTransactionDocs", acl.getAclTransactionDocsInIndex());
        figures.put("uniqueAclTransactionDocs", acl.getUniqueAclTransactionDocsInIndex());
        figures.put("missingAclTx", acl.getMissingAclTxFromIndex().cardinality());
        figures.put("duplicatedAclTx", acl.getDuplicatedAclTxInIndex().cardinality());
        figures.put("aclTxNotInDb", acl.getAclTxInIndexButNotInDb().cardinality());
        for (Map.Entry<String, Object> stat : queryService.getCoreStats())
        {
            figures.put(stat.getKey(), stat.getValue());
        }
        return figures;
    }

    private QueryResponse answer(SolrQuery query)
    {
        List<SolrInputDocument> matching = documents.stream()
                .filter(document -> matches(document, query.getQuery()))
                .toList();
        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(matching.size());
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(results);
        String[] facetFields = query.getFacetFields();
        if (facetFields != null)
        {
            for (String field : facetFields)
            {
                when(response.getFacetField(field)).thenReturn(facet(field, matching, query.getFacetMinCount()));
            }
        }
        return response;
    }

    private static boolean matches(SolrInputDocument document, String query)
    {
        if ("*:*".equals(query))
        {
            return true;
        }
        Matcher range = RANGE.matcher(query);
        if (range.matches())
        {
            Object value = document.getFieldValue(range.group(1));
            if (value == null)
            {
                return false;
            }
            long id = Long.parseLong(value.toString());
            return Long.parseLong(range.group(2)) <= id && id <= Long.parseLong(range.group(3));
        }
        Matcher term = TERM.matcher(query);
        if (term.matches())
        {
            Object value = document.getFieldValue(term.group(1));
            return value != null && term.group(2).equals(value.toString());
        }
        throw new AssertionError("A report issued a query this test does not model: " + query);
    }

    private static FacetField facet(String field, List<SolrInputDocument> matching, int minCount)
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SolrInputDocument document : matching)
        {
            Object value = document.getFieldValue(field);
            if (value != null)
            {
                counts.merge(value.toString(), 1L, Long::sum);
            }
        }
        FacetField facet = new FacetField(field);
        counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= minCount)
                .sorted(Comparator.comparing((Map.Entry<String, Long> entry) -> -entry.getValue())
                        .thenComparing(entry -> sortKey(entry.getKey())))
                .forEach(entry -> facet.add(entry.getKey(), entry.getValue()));
        return facet;
    }

    private static String sortKey(String value)
    {
        return value.matches("\\d+") ? String.format("%020d", Long.parseLong(value)) : value;
    }

    private static SolrInputDocument node(long dbid)
    {
        SolrInputDocument node = new SolrInputDocument();
        node.setField(SolrDocumentMapper.FIELD_SOLR4_ID, "_DEFAULT_!" + dbid);
        node.setField(SolrDocumentMapper.FIELD_DBID, dbid);
        node.setField(SolrDocumentMapper.FIELD_INTXID, dbid - 10);
        node.setField(SolrDocumentMapper.FIELD_DOC_TYPE, SolrDocumentMapper.DOC_TYPE_NODE);
        return node;
    }

    private static StoredDiagnostic stored(String finishedAt)
    {
        return new StoredDiagnostic("2026-09-25T15:02:11Z", "admin", finishedAt,
                Map.of("DB transaction count", 3), Map.of("totalErrorNodes", 1), List.of());
    }
}
