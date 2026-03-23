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

    public SolrJInformationServer(SolrClient solrClient, String collection,
                                  Properties props, DataModelCallback dataModelCallback)
    {
        this.solrClient = solrClient;
        this.collection = collection;
        this.props = props;
        this.dataModelCallback = dataModelCallback;

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
        throw new UnsupportedOperationException("Not yet implemented: dirtyTransaction");
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
        throw new UnsupportedOperationException("Not yet implemented: getIndexCap");
    }

    @Override
    public long nodeCount() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: nodeCount");
    }

    @Override
    public long maxNodeId() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: maxNodeId");
    }

    @Override
    public long minNodeId() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: minNodeId");
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
        throw new UnsupportedOperationException("Not yet implemented: getCascadeNodes");
    }

    @Override
    public long indexAcl(List<AclReaders> aclReaderList, boolean overwrite) throws IOException
    {
        return indexingService.indexAcl(aclReaderList, overwrite);
    }

    @Override
    public TrackerState getTrackerInitialState()
    {
        throw new UnsupportedOperationException("Not yet implemented: getTrackerInitialState");
    }

    @Override
    public void continueState(TrackerState state)
    {
        throw new UnsupportedOperationException("Not yet implemented: continueState");
    }

    @Override
    public int getTxDocsSize(String targetTxId, String targetTxCommitTime) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getTxDocsSize");
    }

    @Override
    public int getRegisteredSearcherCount()
    {
        throw new UnsupportedOperationException("Not yet implemented: getRegisteredSearcherCount");
    }

    @Override
    public boolean txnInIndex(long txnId, boolean populateCache) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: txnInIndex");
    }

    @Override
    public boolean aclChangeSetInIndex(long changeSetId, boolean populateCache) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: aclChangeSetInIndex");
    }

    @Override
    public List<Transaction> getCascades(int num) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getCascades");
    }

    @Override
    public void updateTransaction(Transaction txn) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: updateTransaction");
    }

    @Override
    public void clearProcessedTransactions()
    {
        throw new UnsupportedOperationException("Not yet implemented: clearProcessedTransactions");
    }

    @Override
    public void clearProcessedAclChangeSets()
    {
        throw new UnsupportedOperationException("Not yet implemented: clearProcessedAclChangeSets");
    }

    @Override
    public boolean isInIndex(String id) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: isInIndex");
    }

    @Override
    public void setCleanContentTxnFloor(long cleanContentTxnFloor)
    {
        throw new UnsupportedOperationException("Not yet implemented: setCleanContentTxnFloor");
    }

    @Override
    public void setCleanCascadeTxnFloor(long cleanCascadeTxnFloor)
    {
        throw new UnsupportedOperationException("Not yet implemented: setCleanCascadeTxnFloor");
    }

    @Override
    public Set<Long> getErrorDocIds() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getErrorDocIds");
    }

    @Override
    public Iterable<Map.Entry<String, Object>> getCoreStats() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getCoreStats");
    }

    @Override
    public TrackerStats getTrackerStats()
    {
        throw new UnsupportedOperationException("Not yet implemented: getTrackerStats");
    }

    @Override
    public Map<String, Set<String>> getModelErrors()
    {
        throw new UnsupportedOperationException("Not yet implemented: getModelErrors");
    }

    @Override
    public DictionaryComponent getDictionaryService(String alternativeDictionary)
    {
        throw new UnsupportedOperationException("Not yet implemented: getDictionaryService");
    }

    @Override
    public NamespaceDAO getNamespaceDAO()
    {
        throw new UnsupportedOperationException("Not yet implemented: getNamespaceDAO");
    }

    @Override
    public List<AlfrescoModel> getAlfrescoModels()
    {
        throw new UnsupportedOperationException("Not yet implemented: getAlfrescoModels");
    }

    @Override
    public void afterInitModels()
    {
        throw new UnsupportedOperationException("Not yet implemented: afterInitModels");
    }

    @Override
    public boolean putModel(M2Model model)
    {
        throw new UnsupportedOperationException("Not yet implemented: putModel");
    }

    @Override
    public M2Model getM2Model(QName modelQName)
    {
        throw new UnsupportedOperationException("Not yet implemented: getM2Model");
    }

    @Override
    public long getHoleRetention()
    {
        throw new UnsupportedOperationException("Not yet implemented: getHoleRetention");
    }

    @Override
    public AclReport checkAclInIndex(Long aclid, AclReport aclReport)
    {
        throw new UnsupportedOperationException("Not yet implemented: checkAclInIndex");
    }

    @Override
    public IndexHealthReport reportIndexTransactions(Long minTxId, IOpenBitSet txIdsInDb, long maxTxId) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: reportIndexTransactions");
    }

    @Override
    public List<TenantDbId> getDocsWithUncleanContent() throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getDocsWithUncleanContent");
    }

    @Override
    public void updateContent(TenantDbId docRef) throws Exception
    {
        indexingService.updateContent(docRef);
    }

    @Override
    public void addCommonNodeReportInfo(NodeReport nodeReport)
    {
        throw new UnsupportedOperationException("Not yet implemented: addCommonNodeReportInfo");
    }

    @Override
    public void addContentOutdatedAndUpdatedCounts(Map<String, Object> report)
    {
        throw new UnsupportedOperationException("Not yet implemented: addContentOutdatedAndUpdatedCounts");
    }

    @Override
    public IndexHealthReport reportAclTransactionsInIndex(Long minAclTxId, IOpenBitSet aclTxIdsInDb, long maxAclTxId)
    {
        throw new UnsupportedOperationException("Not yet implemented: reportAclTransactionsInIndex");
    }

    @Override
    public int getAclTxDocsSize(String aclTxId, String aclTxCommitTime) throws IOException
    {
        throw new UnsupportedOperationException("Not yet implemented: getAclTxDocsSize");
    }

    @Override
    public AclChangeSet getMaxAclChangeSetIdAndCommitTimeInIndex()
    {
        throw new UnsupportedOperationException("Not yet implemented: getMaxAclChangeSetIdAndCommitTimeInIndex");
    }

    @Override
    public Transaction getMaxTransactionIdAndCommitTimeInIndex()
    {
        throw new UnsupportedOperationException("Not yet implemented: getMaxTransactionIdAndCommitTimeInIndex");
    }

    @Override
    public void initSkippingDescendantDocs()
    {
        throw new UnsupportedOperationException("Not yet implemented: initSkippingDescendantDocs");
    }

    @Override
    public void registerTrackerThread()
    {
        throw new UnsupportedOperationException("Not yet implemented: registerTrackerThread");
    }

    @Override
    public void unregisterTrackerThread()
    {
        throw new UnsupportedOperationException("Not yet implemented: unregisterTrackerThread");
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
        throw new UnsupportedOperationException("Not yet implemented: cascadeTrackingEnabled");
    }

    @Override
    public TrackerRegistry getTrackerRegistry()
    {
        throw new UnsupportedOperationException("Not yet implemented: getTrackerRegistry");
    }
}
