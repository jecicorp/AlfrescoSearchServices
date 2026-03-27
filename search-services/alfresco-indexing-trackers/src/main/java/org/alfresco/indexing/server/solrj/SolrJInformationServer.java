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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.DataModelCallback;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.repo.dictionary.DictionaryComponent;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.dictionary.NamespaceDAO;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.AclReport;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.adapters.ISimpleOrderedMap;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.AlfrescoModel;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.solr.tracker.TrackerStats;
import org.apache.solr.client.solrj.SolrClient;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link InformationServer} that communicates with Solr
 * remotely via SolrJ HTTP client, rather than embedding Solr in-process.
 */
public class SolrJInformationServer implements InformationServer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrJInformationServer.class);

    private final SolrClient solrClient;
    private final String collection;
    private final Properties props;
    private final DataModelCallback dataModelCallback;

    /** Repository client used for node metadata lookups (e.g. getCascadeNodes). May be null. */
    private final SOLRAPIClient repositoryClient;

    private final SolrJIndexingService indexingService;
    private final SolrJCommitService commitService;
    private final SolrJQueryService queryService;
    private final SolrJModelService modelService;

    private final long lag;
    private final long holeRetention;

    private final TrackerStats trackerStats;

    /**
     * Registry of all trackers. Set after construction via {@link #setTrackerRegistry(TrackerRegistry)}
     * because the registry is typically built after this server is created and trackers are registered.
     */
    private TrackerRegistry trackerRegistry;

    /** Optional NamespaceDAO for QName prefix resolution in remote mode. */
    private NamespaceDAO namespaceDAO;

    /** Local dictionary for property definition lookups. */
    private final LocalDictionaryService localDictionaryService;

    public void setNamespaceDAO(NamespaceDAO namespaceDAO) { this.namespaceDAO = namespaceDAO; }

    public SolrJInformationServer(SolrClient solrClient, String collection,
                                  Properties props, DataModelCallback dataModelCallback)
    {
        this(solrClient, collection, props, dataModelCallback, null, null);
    }

    public SolrJInformationServer(SolrClient solrClient, String collection,
                                  Properties props, DataModelCallback dataModelCallback,
                                  SOLRAPIClient repositoryClient)
    {
        this(solrClient, collection, props, dataModelCallback, repositoryClient, null);
    }

    public SolrJInformationServer(SolrClient solrClient, String collection,
                                  Properties props, DataModelCallback dataModelCallback,
                                  SOLRAPIClient repositoryClient,
                                  LocalDictionaryService localDictionaryService)
    {
        this.solrClient = solrClient;
        this.collection = collection;
        this.props = props;
        this.dataModelCallback = dataModelCallback;
        this.repositoryClient = repositoryClient;

        this.lag = Long.parseLong(props.getProperty("alfresco.lag", "1000"));
        this.holeRetention = Long.parseLong(props.getProperty("alfresco.hole.retention", "3600000"));

        this.trackerStats = new TrackerStats(this);

        this.localDictionaryService = localDictionaryService != null
                ? localDictionaryService : new LocalDictionaryService();
        SolrDocumentMapper documentMapper = new SolrDocumentMapper(
                Boolean.parseBoolean(props.getProperty("alfresco.cascade.tracker.enabled", "true")),
                this.localDictionaryService);
        this.queryService = new SolrJQueryService(solrClient, collection);
        this.indexingService = new SolrJIndexingService(solrClient, collection, documentMapper, repositoryClient,
                this.queryService, this.localDictionaryService);
        this.commitService = new SolrJCommitService(solrClient, collection);
        this.modelService = new SolrJModelService(solrClient, collection);
    }

    /**
     * Sets the tracker registry. Must be called after all trackers have been registered.
     * Used by {@link org.alfresco.indexing.tracker.MetadataTracker} and
     * {@link org.alfresco.indexing.tracker.CascadeTracker} to check if
     * {@link org.alfresco.indexing.tracker.ModelTracker} has loaded models.
     *
     * @param trackerRegistry the tracker registry
     */
    public void setTrackerRegistry(TrackerRegistry trackerRegistry)
    {
        this.trackerRegistry = trackerRegistry;
    }

    // --- InformationServerCollectionProvider methods ---

    @Override
    public IOpenBitSet getOpenBitSetInstance()
    {
        return new JavaBitSetAdapter();
    }

    @Override
    public <T> ISimpleOrderedMap<T> getSimpleOrderedMapInstance()
    {
        return new LinkedHashMapOrderedMap<>();
    }

    // --- InformationServer methods ---

    @Override
    public void rollback() throws IOException
    {
        commitService.rollback();
    }

    @Override
    public void dirtyTransaction(long txnId)
    {
        // In the embedded implementation, this clears local content/cascade caches.
        // In SolrJ mode, content tracking caches are managed differently.
        // No-op for now.
    }

    @Override
    public void commit() throws IOException
    {
        commitService.commit();
    }

    @Override
    public void hardCommit() throws IOException
    {
        commitService.hardCommit();
    }

    @Override
    public boolean commit(boolean openSearcher) throws IOException
    {
        return commitService.commit(openSearcher);
    }

    @Override
    public void indexAclTransaction(AclChangeSet changeSet, boolean overwrite) throws IOException
    {
        indexingService.indexAclTransaction(changeSet, overwrite);
    }

    @Override
    public void indexTransaction(Transaction info, boolean overwrite) throws IOException
    {
        indexingService.indexTransaction(info, overwrite);
    }

    @Override
    public void deleteByTransactionId(Long transactionId) throws IOException
    {
        indexingService.deleteByTransactionId(transactionId);
    }

    @Override
    public void deleteByAclChangeSetId(Long aclChangeSetId) throws IOException
    {
        indexingService.deleteByAclChangeSetId(aclChangeSetId);
    }

    @Override
    public void deleteByAclId(Long aclId) throws IOException
    {
        indexingService.deleteByAclId(aclId);
    }

    @Override
    public void deleteByNodeId(Long nodeId) throws IOException
    {
        indexingService.deleteByNodeId(nodeId);
    }

    @Override
    public void capIndex(long nodeId) throws IOException
    {
        indexingService.capIndex(nodeId);
    }

    @Override
    public void updateTrackerState(long lastTxIdOnServer, long lastTxCommitTimeOnServer) throws IOException
    {
        indexingService.updateTrackerState(lastTxIdOnServer, lastTxCommitTimeOnServer);
    }

    @Override
    public long getIndexCap() throws IOException
    {
        return queryService.getIndexCap();
    }

    @Override
    public long nodeCount() throws IOException
    {
        return queryService.nodeCount();
    }

    @Override
    public long maxNodeId() throws IOException
    {
        return queryService.maxNodeId();
    }

    @Override
    public long minNodeId() throws IOException
    {
        return queryService.minNodeId();
    }

    @Override
    public void maintainCap(long nodeId) throws Exception
    {
        indexingService.maintainCap(nodeId);
    }

    @Override
    public void indexNode(Node node, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        indexingService.indexNode(node, overwrite);
    }

    @Override
    public void indexNodes(List<Node> nodes, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        indexingService.indexNodes(nodes, overwrite);
    }

    @Override
    public void cascadeNodes(List<NodeMetaData> nodes, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        indexingService.cascadeNodes(nodes, overwrite);
    }

    @Override
    public List<NodeMetaData> getCascadeNodes(List<Long> txnIds) throws AuthenticationException, IOException, JSONException
    {
        // Step 1: Query the index for node IDs that have cascade-flag set for the given txnIds.
        Set<Long> parentNodeIds = queryService.getCascadeNodeIds(txnIds);

        if (parentNodeIds.isEmpty())
        {
            return new ArrayList<>();
        }

        // Step 2: Fetch node metadata from the repository for those node IDs.
        if (repositoryClient == null)
        {
            LOGGER.warn("getCascadeNodes: repositoryClient is null — cannot fetch node metadata. " +
                    "Pass a SOLRAPIClient to the SolrJInformationServer constructor. " +
                    "Found {} candidate node IDs but returning empty list.", parentNodeIds.size());
            return new ArrayList<>();
        }

        List<NodeMetaData> allNodeMetaDatas = new ArrayList<>();
        for (Long parentNodeId : parentNodeIds)
        {
            NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
            nmdp.setFromNodeId(parentNodeId);
            nmdp.setToNodeId(parentNodeId);
            nmdp.setIncludeAclId(true);
            nmdp.setIncludeChildAssociations(false);
            nmdp.setIncludeChildIds(true);
            nmdp.setIncludeOwner(false);
            nmdp.setIncludeParentAssociations(false);
            nmdp.setIncludePaths(true);
            nmdp.setIncludeProperties(false);
            nmdp.setIncludeTxnId(true);
            try
            {
                List<NodeMetaData> metaDatas = repositoryClient.getNodesMetaData(nmdp);
                if (metaDatas != null)
                {
                    allNodeMetaDatas.addAll(metaDatas);
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to fetch metadata for cascade node {}: {}", parentNodeId, e.getMessage(), e);
            }
        }
        return allNodeMetaDatas;
    }

    @Override
    public long indexAcl(List<AclReaders> aclReaderList, boolean overwrite) throws IOException
    {
        return indexingService.indexAcl(aclReaderList, overwrite);
    }

    @Override
    public TrackerState getTrackerInitialState()
    {
        return queryService.getTrackerInitialState(lag, holeRetention);
    }

    @Override
    public void continueState(TrackerState state)
    {
        queryService.continueState(state, lag, holeRetention);
    }

    @Override
    public int getTxDocsSize(String targetTxId, String targetTxCommitTime) throws IOException
    {
        return queryService.getTxDocsSize(targetTxId, targetTxCommitTime);
    }

    @Override
    public int getRegisteredSearcherCount()
    {
        // Not meaningful via SolrJ — Solr manages searchers internally
        return 0;
    }

    @Override
    public boolean txnInIndex(long txnId, boolean populateCache) throws IOException
    {
        return queryService.txnInIndex(txnId, populateCache);
    }

    @Override
    public boolean aclChangeSetInIndex(long changeSetId, boolean populateCache) throws IOException
    {
        return queryService.aclChangeSetInIndex(changeSetId, populateCache);
    }

    @Override
    public List<Transaction> getCascades(int num) throws IOException
    {
        return queryService.getCascades(num);
    }

    @Override
    public void updateTransaction(Transaction txn) throws IOException
    {
        // Re-index the transaction with cascade flag cleared (0)
        // so the CascadeTracker does not reprocess it.
        indexingService.updateTransactionCascadeProcessed(txn);
    }

    @Override
    public void clearProcessedTransactions()
    {
        queryService.clearProcessedTransactions();
    }

    @Override
    public void clearProcessedAclChangeSets()
    {
        queryService.clearProcessedAclChangeSets();
    }

    @Override
    public boolean isInIndex(String id) throws IOException
    {
        return queryService.isInIndex(id);
    }

    @Override
    public void setCleanContentTxnFloor(long cleanContentTxnFloor)
    {
        // Content tracking cache management — no-op in SolrJ mode,
        // content tracking is handled differently.
    }

    @Override
    public void setCleanCascadeTxnFloor(long cleanCascadeTxnFloor)
    {
        // Cascade tracking cache management — no-op in SolrJ mode.
    }

    @Override
    public Set<Long> getErrorDocIds() throws IOException
    {
        return queryService.getErrorDocIds();
    }

    @Override
    public Iterable<Map.Entry<String, Object>> getCoreStats() throws IOException
    {
        return queryService.getCoreStats();
    }

    @Override
    public TrackerStats getTrackerStats()
    {
        return trackerStats;
    }

    @Override
    public Map<String, Set<String>> getModelErrors()
    {
        return modelService.getModelErrors();
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Architecture note:</strong> {@code DictionaryComponent} is backed by
     * {@code AlfrescoSolrDataModel}, a Solr-core-embedded singleton.
     * It is not transferable over a SolrJ connection.
     * {@code ModelTracker.expandQNameImpl()} calls this to resolve namespace prefixes —
     * that code path is incompatible with remote (SolrJ) mode.
     * The caller must not invoke this method in remote mode.</p>
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public DictionaryComponent getDictionaryService(String alternativeDictionary)
    {
        throw new UnsupportedOperationException(
                "getDictionaryService is not available in remote (SolrJ) mode: " +
                "DictionaryComponent is backed by AlfrescoSolrDataModel (Solr-embedded singleton). " +
                "ModelTracker namespace expansion is not supported in this mode.");
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Architecture note:</strong> {@code NamespaceDAO} is backed by
     * {@code AlfrescoSolrDataModel}, a Solr-core-embedded singleton.
     * It is not transferable over a SolrJ connection.
     * {@code ModelTracker.expandQNameImpl()} and {@code removeMatchingModels()} call this.
     * In remote mode, returns the local {@link NamespaceDAO} if set, otherwise throws.</p>
     */
    @Override
    public NamespaceDAO getNamespaceDAO()
    {
        if (namespaceDAO != null)
        {
            return namespaceDAO;
        }
        throw new UnsupportedOperationException(
                "getNamespaceDAO is not available: no local NamespaceDAO has been configured.");
    }

    @Override
    public List<AlfrescoModel> getAlfrescoModels()
    {
        return modelService.getAlfrescoModels();
    }

    @Override
    public void afterInitModels()
    {
        modelService.afterInitModels();
    }

    @Override
    public boolean putModel(M2Model model)
    {
        boolean success = modelService.putModel(model);
        // Register the model in the local dictionary for property definition lookups
        localDictionaryService.putModel(model);
        // Register the model's namespaces in the local NamespaceDAO
        // so that SOLRAPIClient.getModelsDiff() can resolve prefix → URI correctly.
        if (success && namespaceDAO != null)
        {
            for (org.alfresco.repo.dictionary.M2Namespace ns : model.getNamespaces())
            {
                namespaceDAO.addPrefix(ns.getPrefix(), ns.getUri());
            }
        }
        return success;
    }

    @Override
    public M2Model getM2Model(QName modelQName)
    {
        return modelService.getM2Model(modelQName);
    }

    @Override
    public long getHoleRetention()
    {
        return holeRetention;
    }

    @Override
    public AclReport checkAclInIndex(Long aclid, AclReport aclReport)
    {
        try
        {
            return queryService.checkAclInIndex(aclid, aclReport);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to check ACL in index", e);
        }
    }

    @Override
    public IndexHealthReport reportIndexTransactions(Long minTxId, IOpenBitSet txIdsInDb, long maxTxId) throws IOException
    {
        return queryService.reportIndexTransactions(minTxId, txIdsInDb, maxTxId, this);
    }

    @Override
    public List<TenantDbId> getDocsWithUncleanContent() throws IOException
    {
        return queryService.getDocsWithUncleanContent();
    }

    @Override
    public List<TenantDbId> getDocsWithIndexingError() throws IOException
    {
        return queryService.getDocsWithIndexingError();
    }

    @Override
    public void markIndexingError(long dbId, String tenant) throws IOException
    {
        indexingService.markIndexingError(dbId, tenant);
    }

    @Override
    public void clearIndexingError(long dbId, String tenant) throws IOException
    {
        indexingService.clearIndexingError(dbId, tenant);
    }

    @Override
    public void updateContent(TenantDbId docRef) throws Exception
    {
        indexingService.updateContent(docRef);
    }

    @Override
    public void addCommonNodeReportInfo(NodeReport nodeReport)
    {
        try
        {
            queryService.addCommonNodeReportInfo(nodeReport);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to add common node report info", e);
        }
    }

    @Override
    public void addContentOutdatedAndUpdatedCounts(Map<String, Object> report)
    {
        // Content versioning stats are not available via SolrJ in the same way.
        // This is a no-op for now.
    }

    @Override
    public IndexHealthReport reportAclTransactionsInIndex(Long minAclTxId, IOpenBitSet aclTxIdsInDb, long maxAclTxId)
    {
        try
        {
            return queryService.reportAclTransactionsInIndex(minAclTxId, aclTxIdsInDb, maxAclTxId, this);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to report ACL transactions in index", e);
        }
    }

    @Override
    public int getAclTxDocsSize(String aclTxId, String aclTxCommitTime) throws IOException
    {
        return queryService.getAclTxDocsSize(aclTxId, aclTxCommitTime);
    }

    @Override
    public AclChangeSet getMaxAclChangeSetIdAndCommitTimeInIndex()
    {
        try
        {
            return queryService.getMaxAclChangeSetIdAndCommitTimeInIndex();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to get max ACL changeset", e);
        }
    }

    @Override
    public Transaction getMaxTransactionIdAndCommitTimeInIndex()
    {
        try
        {
            return queryService.getMaxTransactionIdAndCommitTimeInIndex();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to get max transaction", e);
        }
    }

    @Override
    public void initSkippingDescendantDocs()
    {
        // Skipping descendant docs is configured via core properties and the data model.
        // In SolrJ mode, this is handled server-side. No-op.
    }

    @Override
    public void registerTrackerThread()
    {
        // In the embedded implementation, this registers the current thread for
        // rollback protection. In SolrJ mode, rollback is handled via the commit service.
        // No-op.
    }

    @Override
    public void unregisterTrackerThread()
    {
        // See registerTrackerThread(). No-op in SolrJ mode.
    }

    @Override
    public void reindexNodeByQuery(String query) throws IOException, AuthenticationException, JSONException
    {
        indexingService.reindexNodeByQuery(query);
    }

    @Override
    public int getPort()
    {
        return Integer.parseInt(props.getProperty("alfresco.port", "8983"));
    }

    @Override
    public String getHostName()
    {
        return props.getProperty("alfresco.host", "localhost");
    }

    @Override
    public String getBaseUrl()
    {
        return props.getProperty("alfresco.baseUrl", "/solr");
    }

    @Override
    public boolean cascadeTrackingEnabled()
    {
        return Boolean.parseBoolean(props.getProperty("alfresco.cascade.tracker.enabled", "true"));
    }

    @Override
    public TrackerRegistry getTrackerRegistry()
    {
        return trackerRegistry;
    }
}
