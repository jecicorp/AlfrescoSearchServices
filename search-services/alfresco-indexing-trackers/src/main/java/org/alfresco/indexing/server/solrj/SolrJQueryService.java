/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
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

package org.alfresco.indexing.server.solrj;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ACL;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ACL_TX;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ERROR_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_TX;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_ACLID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_ACLTXCOMMITTIME;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_ACLTXID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_CASCADE_FLAG;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DBID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DOC_TYPE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_INTXID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_SOLR4_ID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_S_ACLTXCOMMITTIME;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_S_ACLTXID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_S_TXCOMMITTIME;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_S_TXID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_TXCOMMITTIME;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_TXID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.alfresco.solr.AclReport;
import org.alfresco.solr.InformationServerCollectionProvider;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.FacetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles query and state-related operations via SolrJ.
 *
 * <p>This service translates the query patterns from {@code SolrInformationServer}
 * into SolrJ {@link SolrClient#query} calls. It covers:
 * <ul>
 *   <li>Tracker initial state retrieval and state continuation</li>
 *   <li>Transaction/ACL changeset presence checks</li>
 *   <li>Node count and min/max node ID queries</li>
 *   <li>Index cap retrieval</li>
 *   <li>Health report generation (transaction and ACL consistency)</li>
 *   <li>Error document discovery</li>
 *   <li>Core statistics</li>
 * </ul>
 */
public class SolrJQueryService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrJQueryService.class);

    // State document IDs — stored in Solr as special documents
    static final String STATE_DOC_TX = "TRACKER!STATE!TX";
    static final String STATE_DOC_ACLTX = "TRACKER!STATE!ACLTX";
    static final String INDEX_CAP_ID = "TRACKER!STATE!CAP";
    static final String PREFIX_ERROR = "ERROR-";

    static final String DOC_TYPE_STATE = "State";
    static final String DOC_TYPE_UNINDEXED_NODE = "UnindexedNode";

    private static final String AND = " AND ";
    private static final int BATCH_FACET_TXS = 4096;
    private static final int DEFAULT_FACET_LIMIT = 100;

    private final SolrClient solrClient;
    private final String collection;

    // In-memory LRU caches, matching the pattern from SolrInformationServer
    private final LRUCache<Long, Object> txnIdCache = new LRUCache<>(64000);
    private final LRUCache<Long, Object> aclChangeSetCache = new LRUCache<>(64000);
    private final LRUCache<String, Boolean> isIdIndexCache = new LRUCache<>(64000);

    public SolrJQueryService(SolrClient solrClient, String collection)
    {
        this.solrClient = solrClient;
        this.collection = collection;
    }

    /**
     * Creates a SolrQuery with the Lucene query parser (defType=lucene).
     * This is required because Alfresco Solr uses AFTS as the default query parser,
     * which does not understand standard Lucene field:value syntax with special characters.
     */
    private static SolrQuery luceneQuery(String q)
    {
        SolrQuery query = new SolrQuery(q);
        query.set("defType", "lucene");
        // Use /query handler to bypass the Alfresco custom SearchHandler
        // which filters stored fields from responses.
        query.set("qt", "/query");
        return query;
    }

    // -------------------------------------------------------------------------
    // Tracker state methods
    // -------------------------------------------------------------------------

    /**
     * Retrieves the tracker initial state by querying the state documents.
     * Mirrors {@code SolrInformationServer.getTrackerInitialState()}.
     *
     * @param lag             indexing lag in milliseconds
     * @param holeRetention   hole retention period in milliseconds
     * @return populated TrackerState
     */
    public TrackerState getTrackerInitialState(long lag, long holeRetention)
    {
        TrackerState state = new TrackerState();
        try
        {
            // Query both state documents by their IDs using standard /select handler
            SolrQuery query = luceneQuery("id:\"" + STATE_DOC_ACLTX + "\" OR id:\"" + STATE_DOC_TX + "\"");
            query.setRows(2);
            query.setFields("*");

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();

            if (docs == null || docs.getNumFound() == 0)
            {
                LOGGER.info("No tracker state documents found in index — first run.");
                // Do NOT return early — fall through to set timing fields
                // (timeToStopIndexing, lastGoodTxCommitTimeInIndex, etc.)
            }
            else
            {

            for (SolrDocument current : docs)
            {
                // ACLTX state document
                if (current.getFieldValue(FIELD_S_ACLTXCOMMITTIME) != null)
                {
                    if (state.getLastIndexedChangeSetCommitTime() == 0)
                    {
                        state.setLastIndexedChangeSetCommitTime(
                                getFieldValueLong(current, FIELD_S_ACLTXCOMMITTIME));
                    }
                    if (state.getLastIndexedChangeSetId() == 0)
                    {
                        state.setLastIndexedChangeSetId(
                                getFieldValueLong(current, FIELD_S_ACLTXID));
                    }
                }

                // TX state document
                if (current.getFieldValue(FIELD_S_TXCOMMITTIME) != null)
                {
                    if (state.getLastIndexedTxCommitTime() == 0)
                    {
                        state.setLastIndexedTxCommitTime(
                                getFieldValueLong(current, FIELD_S_TXCOMMITTIME));
                    }
                    if (state.getLastIndexedTxId() == 0)
                    {
                        state.setLastIndexedTxId(
                                getFieldValueLong(current, FIELD_S_TXID));
                    }
                }
            }
            } // end else (state docs found)
        }
        catch (SolrServerException | IOException e)
        {
            LOGGER.error("Failed to get tracker initial state", e);
        }

        long startTime = System.currentTimeMillis();
        state.setLastStartTime(startTime);
        state.setTimeToStopIndexing(startTime - lag);
        state.setTimeBeforeWhichThereCanBeNoHoles(startTime - holeRetention);

        long timeBeforeWhichThereCanBeNoTxHolesInIndex =
                state.getLastIndexedTxCommitTime() - holeRetention;
        state.setLastGoodTxCommitTimeInIndex(
                timeBeforeWhichThereCanBeNoTxHolesInIndex > 0
                        ? timeBeforeWhichThereCanBeNoTxHolesInIndex : 0);

        long timeBeforeWhichThereCanBeNoChangeSetHolesInIndex =
                state.getLastIndexedChangeSetCommitTime() - holeRetention;
        state.setLastGoodChangeSetCommitTimeInIndex(
                timeBeforeWhichThereCanBeNoChangeSetHolesInIndex > 0
                        ? timeBeforeWhichThereCanBeNoChangeSetHolesInIndex : 0);

        LOGGER.debug("The tracker initial state was created: {}", state);
        return state;
    }

    /**
     * Continues (updates) tracker state for the next tracking cycle.
     * Mirrors {@code SolrInformationServer.continueState(TrackerState)}.
     *
     * @param state          the current tracker state to update
     * @param lag            indexing lag in milliseconds
     * @param holeRetention  hole retention period in milliseconds
     */
    public void continueState(TrackerState state, long lag, long holeRetention)
    {
        long startTime = System.currentTimeMillis();
        long lastStartTime = state.getLastStartTime();
        state.setTimeToStopIndexing(startTime - lag);
        state.setTimeBeforeWhichThereCanBeNoHoles(startTime - holeRetention);

        long timeBeforeWhichThereCanBeNoTxHolesInIndex =
                state.getLastIndexedTxCommitTime() - holeRetention;
        long lastStartTimeWhichThereCanBeNoTxHolesInIndex =
                lastStartTime - holeRetention;

        timeBeforeWhichThereCanBeNoTxHolesInIndex = Math.max(
                timeBeforeWhichThereCanBeNoTxHolesInIndex,
                lastStartTimeWhichThereCanBeNoTxHolesInIndex);

        state.setLastGoodTxCommitTimeInIndex(
                timeBeforeWhichThereCanBeNoTxHolesInIndex > 0
                        ? timeBeforeWhichThereCanBeNoTxHolesInIndex : 0);

        long timeBeforeWhichThereCanBeNoChangeSetHolesInIndex =
                state.getLastIndexedChangeSetCommitTime() - holeRetention;
        long lastStartTimeWhichThereCanBeNoChangeSetHolesInIndex =
                lastStartTime - holeRetention;

        timeBeforeWhichThereCanBeNoChangeSetHolesInIndex = Math.max(
                timeBeforeWhichThereCanBeNoChangeSetHolesInIndex,
                lastStartTimeWhichThereCanBeNoChangeSetHolesInIndex);
        state.setLastGoodChangeSetCommitTimeInIndex(
                timeBeforeWhichThereCanBeNoChangeSetHolesInIndex > 0
                        ? timeBeforeWhichThereCanBeNoChangeSetHolesInIndex : 0);

        state.setLastStartTime(startTime);
    }

    // -------------------------------------------------------------------------
    // Transaction/ACL presence checks
    // -------------------------------------------------------------------------

    /**
     * Checks if a transaction is in the index.
     * Uses a cache to avoid repeated queries.
     */
    public boolean txnInIndex(long txnId, boolean populateCache) throws IOException
    {
        return isInIndex(txnId, txnIdCache, FIELD_TXID, populateCache);
    }

    /**
     * Checks if an ACL change set is in the index.
     * Uses a cache to avoid repeated queries.
     */
    public boolean aclChangeSetInIndex(long changeSetId, boolean populateCache) throws IOException
    {
        return isInIndex(changeSetId, aclChangeSetCache, FIELD_ACLTXID, populateCache);
    }

    /**
     * Checks if a document with the given Solr4 ID is in the index.
     * Mirrors {@code SolrInformationServer.isInIndex(String)}.
     */
    public boolean isInIndex(String id) throws IOException
    {
        Boolean cached = isIdIndexCache.get(id);
        if (cached != null)
        {
            return cached;
        }

        boolean found = isInIndexImpl(id);
        if (found)
        {
            isIdIndexCache.put(id, Boolean.TRUE);
        }
        return found;
    }

    // -------------------------------------------------------------------------
    // Count and stat queries
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of node documents in the index.
     */
    public long nodeCount() throws IOException
    {
        return getDocListSize(FIELD_DOC_TYPE + ":" + DOC_TYPE_NODE);
    }

    /**
     * Returns the maximum DBID among node documents.
     */
    public long maxNodeId() throws IOException
    {
        return topNodeId(SolrQuery.ORDER.desc);
    }

    /**
     * Returns the minimum DBID among node documents.
     */
    public long minNodeId() throws IOException
    {
        return topNodeId(SolrQuery.ORDER.asc);
    }

    /**
     * Returns the transaction document count matching the given txId and commit time.
     */
    public int getTxDocsSize(String targetTxId, String targetTxCommitTime) throws IOException
    {
        return getDocListSize(FIELD_TXID + ":" + targetTxId + AND + FIELD_TXCOMMITTIME + ":" + targetTxCommitTime);
    }

    /**
     * Returns the ACL transaction document count matching the given aclTxId and commit time.
     */
    public int getAclTxDocsSize(String aclTxId, String aclTxCommitTime) throws IOException
    {
        return getDocListSize(FIELD_ACLTXID + ":" + aclTxId + AND + FIELD_ACLTXCOMMITTIME + ":" + aclTxCommitTime);
    }

    // -------------------------------------------------------------------------
    // Max transaction / ACL changeset in index
    // -------------------------------------------------------------------------

    /**
     * Gets the maximum transaction ID and commit time from the tracker state document.
     */
    public Transaction getMaxTransactionIdAndCommitTimeInIndex() throws IOException
    {
        SolrDocument txState = getStateDocument(STATE_DOC_TX);
        Transaction maxTransaction = new Transaction();
        if (txState != null)
        {
            maxTransaction.setId(getFieldValueLong(txState, FIELD_S_TXID));
            maxTransaction.setCommitTimeMs(getFieldValueLong(txState, FIELD_S_TXCOMMITTIME));
        }
        return maxTransaction;
    }

    /**
     * Gets the maximum ACL changeset ID and commit time from the tracker state document.
     */
    public AclChangeSet getMaxAclChangeSetIdAndCommitTimeInIndex() throws IOException
    {
        SolrDocument aclState = getStateDocument(STATE_DOC_ACLTX);
        if (aclState != null)
        {
            long id = getFieldValueLong(aclState, FIELD_S_ACLTXID);
            long commitTime = getFieldValueLong(aclState, FIELD_S_ACLTXCOMMITTIME);
            return new AclChangeSet(id, commitTime, -1);
        }
        return new AclChangeSet(0, 0, -1);
    }

    // -------------------------------------------------------------------------
    // Index cap
    // -------------------------------------------------------------------------

    /**
     * Gets the index cap (max DBID to index up to), stored as a negative DBID
     * in the cap document.
     */
    public long getIndexCap() throws IOException
    {
        try
        {
            SolrQuery query = luceneQuery(FIELD_SOLR4_ID + ":" + INDEX_CAP_ID);
            query.setRows(1);
            query.setFields(FIELD_DBID);

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null && docs.getNumFound() > 0)
            {
                long dbid = getFieldValueLong(docs.get(0), FIELD_DBID);
                return Math.abs(dbid);
            }
            return -1L;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get index cap", e);
        }
    }

    // -------------------------------------------------------------------------
    // Error documents
    // -------------------------------------------------------------------------

    /**
     * Returns the set of node IDs that have error documents in the index.
     */
    public Set<Long> getErrorDocIds() throws IOException
    {
        Set<Long> errorDocIds = new HashSet<>();
        try
        {
            SolrQuery query = luceneQuery(FIELD_DOC_TYPE + ":" + DOC_TYPE_ERROR_NODE);
            query.setRows(Integer.MAX_VALUE);
            query.setFields(FIELD_SOLR4_ID);

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null)
            {
                for (SolrDocument doc : docs)
                {
                    String idString = (String) doc.getFieldValue(FIELD_SOLR4_ID);
                    if (idString != null && idString.startsWith(PREFIX_ERROR))
                    {
                        idString = idString.substring(PREFIX_ERROR.length());
                    }
                    if (idString != null)
                    {
                        errorDocIds.add(Long.valueOf(idString));
                    }
                }
            }
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get error doc IDs", e);
        }
        return errorDocIds;
    }

    // -------------------------------------------------------------------------
    // Unclean content
    // -------------------------------------------------------------------------

    /**
     * Returns documents with unclean (outdated) content.
     * <p>In the SolrJ implementation, this queries for node documents with
     * {@code INTXID} sorted ascending and returns their IDs. The content
     * versioning and cache logic is simplified since we do not have direct
     * access to the Solr searcher and numeric doc values.</p>
     *
     * <p>TODO: The full implementation requires content versioning fields
     * (LAST_INCOMING_CONTENT_VERSION_ID) which are Solr-internal. For now,
     * this returns an empty list as content tracking via SolrJ is deferred.</p>
     */
    public List<org.alfresco.solr.client.TenantDbId> getDocsWithUncleanContent() throws IOException
    {
        // Content tracking via SolrJ requires access to numeric doc values and
        // content versioning fields. This is deferred to a dedicated content
        // tracking mechanism.
        LOGGER.warn("getDocsWithUncleanContent() via SolrJ is not yet fully implemented. Returning empty list.");
        return new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Cascade queries
    // -------------------------------------------------------------------------

    /**
     * Returns up to {@code num} transactions that have cascade-flagged documents.
     * <p>Queries for documents with cascade flag = 1, sorted by TXID.</p>
     */
    public List<Transaction> getCascades(int num) throws IOException
    {
        List<Transaction> transactions = new ArrayList<>();
        try
        {
            SolrQuery query = luceneQuery(FIELD_CASCADE_FLAG + ":1");
            query.setRows(num);
            query.addSort(FIELD_TXID, SolrQuery.ORDER.asc);
            query.setFields(FIELD_S_TXID, FIELD_S_TXCOMMITTIME);

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null)
            {
                for (SolrDocument doc : docs)
                {
                    Transaction txn = new Transaction();
                    txn.setId(getFieldValueLong(doc, FIELD_S_TXID));
                    txn.setCommitTimeMs(getFieldValueLong(doc, FIELD_S_TXCOMMITTIME));
                    transactions.add(txn);
                }
            }
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get cascades", e);
        }
        return transactions;
    }

    /**
     * Returns the set of parent node DB IDs that have the cascade flag set for any of the given transaction IDs.
     *
     * <p>This is the first half of {@code getCascadeNodes}: it queries the index for node documents
     * flagged for cascade update (cascade flag = txnId) for the given transaction IDs, and returns
     * the set of DBID values. The caller is responsible for fetching node metadata from the repository.</p>
     *
     * <p>Mirrors the Lucene query logic in
     * {@code SolrInformationServer.getCascadeNodes(List)} that uses
     * {@code PROP_CASCADE_TX} field.</p>
     *
     * @param txnIds the transaction IDs to look up
     * @return set of node DB IDs that need cascade updates
     * @throws IOException if the Solr query fails
     */
    public Set<Long> getCascadeNodeIds(List<Long> txnIds) throws IOException
    {
        Set<Long> nodeIds = new HashSet<>();
        if (txnIds == null || txnIds.isEmpty())
        {
            return nodeIds;
        }

        try
        {
            // Build a Solr OR query: int@s_@cascade:(txnId1 OR txnId2 OR ...)
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append(FIELD_CASCADE_FLAG).append(":(");
            for (int i = 0; i < txnIds.size(); i++)
            {
                if (i > 0)
                {
                    queryBuilder.append(" OR ");
                }
                queryBuilder.append(txnIds.get(i));
            }
            queryBuilder.append(")");
            queryBuilder.append(AND).append(FIELD_DOC_TYPE).append(":").append(DOC_TYPE_NODE);

            SolrQuery query = luceneQuery(queryBuilder.toString());
            query.setRows(Integer.MAX_VALUE);
            query.setFields(FIELD_DBID);

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null)
            {
                for (SolrDocument doc : docs)
                {
                    Object dbidValue = doc.getFieldValue(FIELD_DBID);
                    if (dbidValue instanceof Number)
                    {
                        nodeIds.add(((Number) dbidValue).longValue());
                    }
                }
            }
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get cascade node IDs for txnIds " + txnIds, e);
        }
        return nodeIds;
    }

    // -------------------------------------------------------------------------
    // Health check / ACL check
    // -------------------------------------------------------------------------

    /**
     * Checks an ACL in the index by counting documents with the given ACL ID.
     */
    public AclReport checkAclInIndex(Long aclid, AclReport aclReport) throws IOException
    {
        String query = FIELD_ACLID + ":" + aclid + AND + FIELD_DOC_TYPE + ":" + DOC_TYPE_ACL;
        long count = getDocListSize(query);
        aclReport.setIndexedAclDocCount(count);
        return aclReport;
    }

    /**
     * Adds common node report info (indexed node doc count).
     */
    public void addCommonNodeReportInfo(NodeReport nodeReport) throws IOException
    {
        long dbId = nodeReport.getDbid();
        String query = FIELD_DBID + ":" + dbId + AND + FIELD_DOC_TYPE + ":" + DOC_TYPE_NODE;
        long count = getDocListSize(query);
        nodeReport.setIndexedNodeDocCount(count);
    }

    /**
     * Reports index transactions health by comparing transaction IDs in the index
     * with those in the database.
     */
    public IndexHealthReport reportIndexTransactions(Long minTxId, IOpenBitSet txIdsInDb,
                                                      long maxTxId,
                                                      InformationServerCollectionProvider collectionProvider)
            throws IOException
    {
        IndexHealthReport report = new IndexHealthReport(collectionProvider);

        // Get document type counts via facets
        Map<String, Long> docTypeCounts = getDocTypeFacetCounts();

        // TX report
        reportTransactionInfo(minTxId, maxTxId, txIdsInDb, FIELD_TXID,
                new TransactionInfoCallbacks()
                {
                    public void idInIndexButNotInDb(long id) { report.setTxInIndexButNotInDb(id); }
                    public void idInDbButNotInIndex(long id) { report.setMissingTxFromIndex(id); }
                    public void duplicatedIdInIndex(long id) { report.setDuplicatedTxInIndex(id); }
                    public void uniqueIdsInIndex(long count) { report.setUniqueTransactionDocsInIndex(count); }
                });

        report.setTransactionDocsInIndex(getSafeCount(docTypeCounts, DOC_TYPE_TX));
        report.setDbTransactionCount(txIdsInDb.cardinality());

        // NODE duplicates
        setDuplicates(DOC_TYPE_NODE, report::setDuplicatedLeafInIndex);
        report.setLeafDocCountInIndex(getSafeCount(docTypeCounts, DOC_TYPE_NODE));

        // ERROR duplicates
        setDuplicates(DOC_TYPE_ERROR_NODE, report::setDuplicatedErrorInIndex);
        report.setErrorDocCountInIndex(getSafeCount(docTypeCounts, DOC_TYPE_ERROR_NODE));

        // UNINDEXED duplicates
        setDuplicates(DOC_TYPE_UNINDEXED_NODE, report::setDuplicatedUnindexedInIndex);
        report.setUnindexedDocCountInIndex(getSafeCount(docTypeCounts, DOC_TYPE_UNINDEXED_NODE));

        return report;
    }

    /**
     * Reports ACL transaction health by comparing ACL changeset IDs in the index
     * with those in the database.
     */
    public IndexHealthReport reportAclTransactionsInIndex(Long minAclTxId, IOpenBitSet aclTxIdsInDb,
                                                           long maxAclTxId,
                                                           InformationServerCollectionProvider collectionProvider)
            throws IOException
    {
        IndexHealthReport report = new IndexHealthReport(collectionProvider);

        Map<String, Long> docTypeCounts = getDocTypeFacetCounts();

        reportTransactionInfo(minAclTxId, maxAclTxId, aclTxIdsInDb, FIELD_ACLTXID,
                new TransactionInfoCallbacks()
                {
                    public void idInIndexButNotInDb(long id) { report.setAclTxInIndexButNotInDb(id); }
                    public void idInDbButNotInIndex(long id) { report.setMissingAclTxFromIndex(id); }
                    public void duplicatedIdInIndex(long id) { report.setDuplicatedAclTxInIndex(id); }
                    public void uniqueIdsInIndex(long count) { report.setUniqueAclTransactionDocsInIndex(count); }
                });

        report.setAclTransactionDocsInIndex(getSafeCount(docTypeCounts, DOC_TYPE_ACL_TX));
        report.setDbAclTransactionCount(aclTxIdsInDb.cardinality());

        return report;
    }

    // -------------------------------------------------------------------------
    // Core stats
    // -------------------------------------------------------------------------

    /**
     * Returns core statistics as key-value pairs.
     * <p>Simplified compared to the embedded implementation: only document type
     * counts are available via SolrJ. Cache and searcher stats require
     * Solr JMX/admin API calls.</p>
     */
    public Iterable<Map.Entry<String, Object>> getCoreStats() throws IOException
    {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> docTypeCounts = getDocTypeFacetCounts();

        stats.put("Alfresco Acls in Index", getSafeCount(docTypeCounts, DOC_TYPE_ACL));
        stats.put("Alfresco Nodes in Index", getSafeCount(docTypeCounts, DOC_TYPE_NODE));
        stats.put("Alfresco Transactions in Index", getSafeCount(docTypeCounts, DOC_TYPE_TX));
        stats.put("Alfresco Acl Transactions in Index", getSafeCount(docTypeCounts, DOC_TYPE_ACL_TX));
        stats.put("Alfresco States in Index", getSafeCount(docTypeCounts, DOC_TYPE_STATE));
        stats.put("Alfresco Unindexed Nodes", getSafeCount(docTypeCounts, DOC_TYPE_UNINDEXED_NODE));
        stats.put("Alfresco Error Nodes in Index", getSafeCount(docTypeCounts, DOC_TYPE_ERROR_NODE));

        return stats.entrySet();
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Clears the in-memory transaction ID cache.
     */
    public void clearProcessedTransactions()
    {
        txnIdCache.clear();
    }

    /**
     * Clears the in-memory ACL changeset ID cache.
     */
    public void clearProcessedAclChangeSets()
    {
        aclChangeSetCache.clear();
    }

    // =========================================================================
    // Private helper methods
    // =========================================================================

    /**
     * Retrieves a state document by its ID using the /get handler (real-time get).
     */
    SolrDocument getStateDocument(String id) throws IOException
    {
        try
        {
            SolrQuery query = luceneQuery("id:\"" + id + "\"");
            query.setRows(1);
            query.setFields("*");

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null && docs.getNumFound() > 0)
            {
                return docs.get(0);
            }
            return null;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get state document: " + id, e);
        }
    }

    /**
     * Checks whether a document with the given numeric ID exists in a given field.
     */
    private boolean isInIndex(long id, LRUCache<Long, Object> cache, String fieldName,
                              boolean populateCache) throws IOException
    {
        if (cache.containsKey(id))
        {
            return true;
        }

        try
        {
            SolrQuery query = luceneQuery(fieldName + ":" + id);
            query.setRows(0);
            QueryResponse response = solrClient.query(collection, query);
            boolean found = response.getResults().getNumFound() > 0;
            if (found && populateCache)
            {
                cache.put(id, null);
            }
            return found;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to check if " + fieldName + ":" + id + " is in index", e);
        }
    }

    /**
     * Checks whether a document with the given Solr4 ID exists in the index.
     */
    private boolean isInIndexImpl(String id) throws IOException
    {
        try
        {
            SolrQuery query = luceneQuery("id:\"" + id + "\"");
            query.setRows(0);

            QueryResponse response = solrClient.query(collection, query);
            return response.getResults() != null && response.getResults().getNumFound() > 0;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to check if " + id + " is in index", e);
        }
    }

    /**
     * Returns the number of documents matching a query.
     */
    int getDocListSize(String queryStr) throws IOException
    {
        try
        {
            SolrQuery query = luceneQuery(queryStr);
            query.setRows(0);
            QueryResponse response = solrClient.query(collection, query);
            return (int) response.getResults().getNumFound();
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get doc list size for query: " + queryStr, e);
        }
    }

    /**
     * Returns the top (min or max) DBID among node documents.
     */
    private long topNodeId(SolrQuery.ORDER order) throws IOException
    {
        try
        {
            SolrQuery query = luceneQuery("*:*");
            query.addFilterQuery(FIELD_DOC_TYPE + ":" + DOC_TYPE_NODE);
            query.setRows(1);
            query.addSort(FIELD_DBID, order);
            query.setFields(FIELD_DBID);

            QueryResponse response = solrClient.query(collection, query);
            SolrDocumentList docs = response.getResults();
            if (docs != null && docs.getNumFound() > 0)
            {
                return getFieldValueLong(docs.get(0), FIELD_DBID);
            }
            return 0L;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get top node ID", e);
        }
    }

    /**
     * Gets a facet count map for the DOC_TYPE field.
     */
    private Map<String, Long> getDocTypeFacetCounts() throws IOException
    {
        return getFacetCounts("*:*", FIELD_DOC_TYPE, 0, DEFAULT_FACET_LIMIT);
    }

    /**
     * Executes a faceted query and returns the facet values as a map.
     */
    private Map<String, Long> getFacetCounts(String queryStr, String facetField,
                                              int minCount, int limit) throws IOException
    {
        Map<String, Long> result = new LinkedHashMap<>();
        try
        {
            SolrQuery query = luceneQuery(queryStr);
            query.setRows(0);
            query.setFacet(true);
            query.addFacetField(facetField);
            query.setFacetLimit(limit);
            query.setFacetMinCount(minCount);

            QueryResponse response = solrClient.query(collection, query);
            FacetField ff = response.getFacetField(facetField);
            if (ff != null)
            {
                for (FacetField.Count count : ff.getValues())
                {
                    result.put(count.getName(), count.getCount());
                }
            }
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to get facets for " + queryStr, e);
        }
        return result;
    }

    /**
     * Gets a safe count from a facet count map, returning 0 if not found.
     */
    private long getSafeCount(Map<String, Long> counts, String key)
    {
        Long value = counts.get(key);
        return value != null ? value : 0L;
    }

    /**
     * Reports transaction info by iterating over ID ranges and comparing
     * the index contents with the database IDs using faceted queries.
     */
    private void reportTransactionInfo(Long minId, long maxId, IOpenBitSet idsInDb,
                                        String field, TransactionInfoCallbacks callbacks)
            throws IOException
    {
        if (minId == null)
        {
            return;
        }

        IOpenBitSet idsInIndex = new JavaBitSetAdapter();
        long batchStartId = minId;
        long batchEndId = Math.min(batchStartId + BATCH_FACET_TXS, maxId);

        while (batchStartId <= maxId)
        {
            long iterationStart = batchStartId;
            String queryStr = field + ":[" + batchStartId + " TO " + batchEndId + "]";
            Map<String, Long> idCounts = getFacetCounts(queryStr, field, 1,
                    (int) Math.min(maxId, Integer.MAX_VALUE));

            for (Map.Entry<String, Long> entry : idCounts.entrySet())
            {
                long idInIndex = Long.parseLong(entry.getKey());

                if (batchStartId <= idInIndex && idInIndex <= batchEndId)
                {
                    idsInIndex.set(idInIndex);

                    for (long id = iterationStart; id <= idInIndex; id++)
                    {
                        if (id == idInIndex)
                        {
                            iterationStart = idInIndex + 1;
                            if (!idsInDb.get(id))
                            {
                                callbacks.idInIndexButNotInDb(id);
                            }
                        }
                        else if (idsInDb.get(id))
                        {
                            callbacks.idInDbButNotInIndex(id);
                        }
                    }

                    if (entry.getValue() > 1)
                    {
                        callbacks.duplicatedIdInIndex(idInIndex);
                    }
                }
                else
                {
                    break;
                }
            }

            // Check remaining IDs in the batch range
            for (long id = iterationStart; id <= batchEndId; id++)
            {
                if (idsInDb.get(id))
                {
                    callbacks.idInDbButNotInIndex(id);
                }
            }

            batchStartId = batchEndId + 1;
            batchEndId = Math.min(batchStartId + BATCH_FACET_TXS, maxId);
        }

        callbacks.uniqueIdsInIndex(idsInIndex.cardinality());
    }

    /**
     * Checks for duplicate documents of a given type by querying with facets
     * having mincount=2 on DBID.
     */
    private void setDuplicates(String docType, DuplicateReporter reporter) throws IOException
    {
        Map<String, Long> dbIdCounts = getFacetCounts(
                FIELD_DOC_TYPE + ":" + docType, FIELD_DBID, 2, DEFAULT_FACET_LIMIT);
        for (Map.Entry<String, Long> entry : dbIdCounts.entrySet())
        {
            long duplicatedDbId = Long.parseLong(entry.getKey());
            reporter.report(duplicatedDbId);
        }
    }

    /**
     * Extracts a long value from a SolrDocument field.
     * Handles both numeric and string representations.
     */
    static long getFieldValueLong(SolrDocument doc, String fieldName)
    {
        Object value = doc.getFieldValue(fieldName);
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        if (value != null)
        {
            return Long.parseLong(value.toString());
        }
        return 0L;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Callback interface for transaction info reporting.
     */
    interface TransactionInfoCallbacks
    {
        void idInIndexButNotInDb(long id);
        void idInDbButNotInIndex(long id);
        void duplicatedIdInIndex(long id);
        void uniqueIdsInIndex(long count);
    }

    /**
     * Functional interface for duplicate reporting.
     */
    @FunctionalInterface
    interface DuplicateReporter
    {
        void report(long dbId);
    }

    /**
     * Simple LRU cache backed by a synchronized LinkedHashMap.
     */
    static class LRUCache<K, V>
    {
        private final Map<K, V> map;

        LRUCache(int maxSize)
        {
            this.map = java.util.Collections.synchronizedMap(
                    new LinkedHashMap<K, V>(maxSize, 0.75f, true)
                    {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
                        {
                            return size() > maxSize;
                        }
                    });
        }

        V get(K key)
        {
            return map.get(key);
        }

        void put(K key, V value)
        {
            map.put(key, value);
        }

        boolean containsKey(K key)
        {
            return map.containsKey(key);
        }

        void clear()
        {
            map.clear();
        }
    }
}
