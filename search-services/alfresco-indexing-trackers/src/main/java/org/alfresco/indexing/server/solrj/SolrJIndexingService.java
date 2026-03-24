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
import java.util.Collections;
import java.util.List;

import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.solr.client.Transaction;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles indexing (add/update/delete) operations via SolrJ.
 *
 * <p>Deletion methods use {@link SolrClient#deleteByQuery(String, String)} with
 * the same field-based query strings as {@code SolrInformationServer}.</p>
 *
 * <p>Indexing methods use {@link SolrDocumentMapper} to build
 * {@link SolrInputDocument} instances, then send them via
 * {@link SolrClient#add(String, SolrInputDocument)}.</p>
 */
public class SolrJIndexingService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrJIndexingService.class);

    /** Special document ID for the index cap state document. */
    static final String INDEX_CAP_ID = "TRACKER!STATE!CAP";

    /** Document type for state documents (cap, tracker state, etc.). */
    static final String DOC_TYPE_STATE = "State";

    private final SolrClient solrClient;
    private final String collection;
    private final SolrDocumentMapper documentMapper;
    private final SOLRAPIClient repositoryClient;

    public SolrJIndexingService(SolrClient solrClient, String collection)
    {
        this(solrClient, collection, new SolrDocumentMapper(), null);
    }

    public SolrJIndexingService(SolrClient solrClient, String collection, SolrDocumentMapper documentMapper)
    {
        this(solrClient, collection, documentMapper, null);
    }

    public SolrJIndexingService(SolrClient solrClient, String collection,
                                SolrDocumentMapper documentMapper, SOLRAPIClient repositoryClient)
    {
        this.solrClient = solrClient;
        this.collection = collection;
        this.documentMapper = documentMapper;
        this.repositoryClient = repositoryClient;
    }

    // =========================================================================
    // Deletion methods
    // =========================================================================

    /**
     * Deletes all documents belonging to a transaction.
     * Query: {@code INTXID:<transactionId>}
     */
    public void deleteByTransactionId(Long transactionId) throws IOException
    {
        deleteByQuery(SolrDocumentMapper.FIELD_INTXID + ":" + transactionId);
    }

    /**
     * Deletes all documents belonging to an ACL change set.
     * Query: {@code INACLTXID:<aclChangeSetId>}
     */
    public void deleteByAclChangeSetId(Long aclChangeSetId) throws IOException
    {
        deleteByQuery(SolrDocumentMapper.FIELD_INACLTXID + ":" + aclChangeSetId);
    }

    /**
     * Deletes all documents for a given ACL.
     * Query: {@code ACLID:<aclId>}
     */
    public void deleteByAclId(Long aclId) throws IOException
    {
        deleteByQuery(SolrDocumentMapper.FIELD_ACLID + ":" + aclId);
    }

    /**
     * Deletes all documents for a given node.
     * Query: {@code DBID:<nodeId>}
     */
    public void deleteByNodeId(Long nodeId) throws IOException
    {
        deleteByQuery(SolrDocumentMapper.FIELD_DBID + ":" + nodeId);
    }

    /**
     * Deletes all node documents with DBID greater than the given value.
     * Query: {@code DBID:{<dbid> TO *}}
     */
    public void maintainCap(long dbid) throws IOException
    {
        deleteByQuery(SolrDocumentMapper.FIELD_DBID + ":{" + dbid + " TO *}");
    }

    // =========================================================================
    // Indexing methods
    // =========================================================================

    /**
     * Indexes a transaction document. If {@code overwrite} is true, the document
     * will replace any existing document with the same ID (Solr's default behavior
     * when sending a document with the same unique key).
     */
    public void indexTransaction(Transaction info, boolean overwrite) throws IOException
    {
        SolrInputDocument doc = documentMapper.toTransactionDoc(info);
        addDocument(doc);
    }

    /**
     * Indexes an ACL change set document.
     */
    public void indexAclTransaction(AclChangeSet changeSet, boolean overwrite) throws IOException
    {
        SolrInputDocument doc = documentMapper.toAclChangeSetDoc(changeSet);
        addDocument(doc);
    }

    /**
     * Indexes ACL documents. Returns the elapsed time in nanoseconds.
     *
     * @param aclReaderList the list of ACL readers to index
     * @param overwrite     whether to overwrite existing documents
     * @return elapsed time in nanoseconds
     */
    public long indexAcl(List<AclReaders> aclReaderList, boolean overwrite) throws IOException
    {
        long start = System.nanoTime();
        List<SolrInputDocument> docs = documentMapper.toAclDocs(aclReaderList);
        if (!docs.isEmpty())
        {
            addDocuments(docs);
        }
        return System.nanoTime() - start;
    }

    /**
     * Indexes a single node. This simplified implementation creates a structural
     * document using {@link SolrDocumentMapper#toNodeDoc(Node, NodeMetaData)}.
     *
     * <p>In the full implementation, this would handle node status (DELETED, UPDATED,
     * UNKNOWN, NON_SHARD_*), fetch metadata from the repository, handle content
     * extraction, etc. For now, only UPDATED/UNKNOWN status nodes with externally
     * provided metadata are supported as a skeleton.</p>
     *
     * <p>For deleted nodes, the node document is removed by DBID.</p>
     */
    public void indexNode(Node node, boolean overwrite) throws IOException
    {
        Node.SolrApiNodeStatus status = node.getStatus();

        if (status == Node.SolrApiNodeStatus.DELETED
                || status == Node.SolrApiNodeStatus.NON_SHARD_DELETED)
        {
            deleteByNodeId(node.getId());
            return;
        }

        if (repositoryClient == null)
        {
            LOGGER.warn("indexNode: repositoryClient is null — cannot fetch metadata for node {}. "
                    + "Pass a SOLRAPIClient to the SolrJIndexingService constructor.", node.getId());
            return;
        }

        NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
        nmdp.setNodeIds(Collections.singletonList(node.getId()));
        nmdp.setMaxResults(1);

        try
        {
            List<NodeMetaData> metadatas = repositoryClient.getNodesMetaData(nmdp);
            if (metadatas == null || metadatas.isEmpty())
            {
                LOGGER.warn("No metadata returned for node {}", node.getId());
                return;
            }
            indexNode(node, metadatas.get(0), overwrite);
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IOException("Failed to fetch metadata for node " + node.getId(), e);
        }
    }

    /**
     * Indexes a single node with the given metadata. This is the method that
     * should be called when metadata is already available (e.g., from the tracker).
     */
    public void indexNode(Node node, NodeMetaData metadata, boolean overwrite) throws IOException
    {
        Node.SolrApiNodeStatus status = node.getStatus();

        if (status == Node.SolrApiNodeStatus.DELETED
                || status == Node.SolrApiNodeStatus.NON_SHARD_DELETED)
        {
            deleteByNodeId(node.getId());
            return;
        }

        SolrInputDocument doc = documentMapper.toNodeDoc(node, metadata);
        addDocument(doc);
    }

    /**
     * Batch indexes nodes. Delegates to {@link #indexNode(Node, boolean)} for each node.
     */
    public void indexNodes(List<Node> nodes, boolean overwrite) throws IOException
    {
        for (Node node : nodes)
        {
            indexNode(node, overwrite);
        }
    }

    /**
     * Processes cascade updates for parent nodes. In the embedded implementation,
     * this updates child documents with new path/ancestor information when a parent
     * node changes.
     *
     * <p>In the remote implementation, this is a placeholder that logs a warning.
     * Full cascade update support requires querying the index for child documents
     * and performing partial updates, which will be implemented incrementally.</p>
     */
    public void cascadeNodes(List<NodeMetaData> nodeMetaDatas, boolean overwrite) throws IOException
    {
        // Cascade updates require querying the index for children of each node
        // and doing partial updates. This is complex and will be implemented later.
        LOGGER.debug("cascadeNodes called for {} nodes. "
                + "Cascade updates not yet fully implemented in remote mode.", nodeMetaDatas.size());
    }

    /**
     * Updates text content for a document. In the embedded implementation, this
     * performs a partial update with extracted text content.
     *
     * <p>In the remote implementation, this creates a partial update document
     * with the structural fields. Full content extraction will be handled by
     * a Solr-side update processor or separate content extraction service.</p>
     */
    public void updateContent(TenantDbId docRef) throws IOException
    {
        LOGGER.debug("updateContent called for DBID={}. "
                + "Content update not yet fully implemented in remote mode.", docRef.dbId);
    }

    /**
     * Creates a special "cap" state document that records the index cap DBID.
     * The DBID is stored as a negative value to distinguish it from real node DBIDs.
     */
    public void capIndex(long dbid) throws IOException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(SolrDocumentMapper.FIELD_SOLR4_ID, INDEX_CAP_ID);
        doc.addField(SolrDocumentMapper.FIELD_VERSION, 0);
        doc.addField(SolrDocumentMapper.FIELD_DBID, -dbid);
        doc.addField(SolrDocumentMapper.FIELD_DOC_TYPE, DOC_TYPE_STATE);
        addDocument(doc);
    }

    /**
     * Re-indexes nodes matching the given query. In the embedded implementation,
     * this queries the local index, extracts DBIDs, and re-indexes those nodes.
     *
     * <p>In the remote implementation, this is not yet supported because it requires
     * querying the remote Solr for matching document DBIDs, then fetching metadata
     * from the repository, and re-indexing. This will be implemented when
     * {@code SolrJQueryService} provides the necessary query support.</p>
     */
    public void reindexNodeByQuery(String query) throws IOException
    {
        LOGGER.warn("reindexNodeByQuery is not yet implemented in remote mode. Query: {}", query);
        throw new UnsupportedOperationException(
                "reindexNodeByQuery is not yet implemented in remote mode");
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void addDocument(SolrInputDocument doc) throws IOException
    {
        try
        {
            solrClient.add(collection, doc);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to add document to Solr collection '" + collection + "'", e);
        }
    }

    private void addDocuments(List<SolrInputDocument> docs) throws IOException
    {
        try
        {
            solrClient.add(collection, docs);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to add documents to Solr collection '" + collection + "'", e);
        }
    }

    private void deleteByQuery(String query) throws IOException
    {
        try
        {
            solrClient.deleteByQuery(collection, query);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to delete by query '" + query + "' from Solr collection '" + collection + "'", e);
        }
    }
}
