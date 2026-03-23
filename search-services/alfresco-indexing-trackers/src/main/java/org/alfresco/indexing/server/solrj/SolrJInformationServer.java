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
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.solr.tracker.TrackerStats;
import org.apache.solr.client.solrj.SolrClient;
import org.json.JSONException;

/**
 * Implementation of {@link InformationServer} that communicates with Solr
 * remotely via SolrJ HTTP client, rather than embedding Solr in-process.
 */
public class SolrJInformationServer implements InformationServer
{
    private final SolrClient solrClient;
    private final String collection;
    private final Properties props;
    private final DataModelCallback dataModelCallback;

    private final SolrJIndexingService indexingService;
    private final SolrJCommitService commitService;
    private final SolrJQueryService queryService;
    private final SolrJModelService modelService;

    private final long lag;
    private final long holeRetention;

    private final TrackerStats trackerStats;

    public SolrJInformationServer(SolrClient solrClient, String collection,
                                  Properties props, DataModelCallback dataModelCallback)
    {
        this.solrClient = solrClient;
        this.collection = collection;
        this.props = props;
        this.dataModelCallback = dataModelCallback;

        this.lag = Long.parseLong(props.getProperty("alfresco.lag", "1000"));
        this.holeRetention = Long.parseLong(props.getProperty("alfresco.hole.retention", "3600000"));

        this.trackerStats = new TrackerStats(this);

        this.indexingService = new SolrJIndexingService(solrClient, collection);
        this.commitService = new SolrJCommitService(solrClient, collection);
        this.queryService = new SolrJQueryService(solrClient, collection);
        this.modelService = new SolrJModelService(solrClient, collection);
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
        // Cascade node lookup requires fetching node metadata from the repository.
        // This is delegated to the tracker's repository client, not the index query service.
        throw new UnsupportedOperationException("Not yet implemented: getCascadeNodes requires repository client");
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
        indexingService.indexTransaction(txn, true);
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

    @Override
    public DictionaryComponent getDictionaryService(String alternativeDictionary)
    {
        // DictionaryComponent is a complex in-process object backed by AlfrescoSolrDataModel.
        // It is not available remotely via SolrJ — trackers must not call this in remote mode.
        throw new UnsupportedOperationException(
                "getDictionaryService is not available in remote (SolrJ) mode");
    }

    @Override
    public NamespaceDAO getNamespaceDAO()
    {
        // NamespaceDAO is a complex in-process object backed by AlfrescoSolrDataModel.
        // It is not available remotely via SolrJ — trackers must not call this in remote mode.
        throw new UnsupportedOperationException(
                "getNamespaceDAO is not available in remote (SolrJ) mode");
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
        return modelService.putModel(model);
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
        throw new UnsupportedOperationException("Not yet implemented: getPort");
    }

    @Override
    public String getHostName()
    {
        throw new UnsupportedOperationException("Not yet implemented: getHostName");
    }

    @Override
    public String getBaseUrl()
    {
        throw new UnsupportedOperationException("Not yet implemented: getBaseUrl");
    }

    @Override
    public boolean cascadeTrackingEnabled()
    {
        return Boolean.parseBoolean(props.getProperty("alfresco.cascade.tracker.enabled", "true"));
    }

    @Override
    public TrackerRegistry getTrackerRegistry()
    {
        throw new UnsupportedOperationException("Not yet implemented: getTrackerRegistry");
    }
}
