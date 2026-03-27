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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.ContentPropertyValue;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.SOLRAPIClient.GetTextContentResponse;
import org.alfresco.solr.client.SOLRAPIClient.SolrApiContentStatus;
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

    /** Maximum content size to read from the repository (10 MB). */
    private static final long DEFAULT_CONTENT_STREAM_LIMIT = 10L * 1024 * 1024;

    private final SolrClient solrClient;
    private final String collection;
    private final SolrDocumentMapper documentMapper;
    private final SOLRAPIClient repositoryClient;
    private long contentStreamLimit = DEFAULT_CONTENT_STREAM_LIMIT;

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
     * Re-indexes a transaction with the cascade flag set to 0 (processed).
     * Called by the CascadeTracker after cascade updates have been applied.
     */
    public void updateTransactionCascadeProcessed(Transaction info) throws IOException
    {
        SolrInputDocument doc = documentMapper.toTransactionDoc(info);
        doc.setField(SolrDocumentMapper.FIELD_CASCADE_FLAG, 0);
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
            try
            {
                indexNode(node, overwrite);
            }
            catch (Exception e)
            {
                LOGGER.warn("Failed to index node {} — skipping: {}", node.getId(), e.getMessage(), e);
            }
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
     * Updates text content for a document by fetching transformed text from the
     * Alfresco Repository and re-indexing the full document with content fields.
     *
     * <p>The flow is:
     * <ol>
     *   <li>Fetch node metadata from the repository</li>
     *   <li>Rebuild the full SolrInputDocument (metadata + structural fields)</li>
     *   <li>For each ContentPropertyValue, fetch text content from the repository</li>
     *   <li>Add content fields (text, transform status/exception/duration)</li>
     *   <li>Mark as synchronized and send to Solr</li>
     * </ol>
     */
    public void updateContent(TenantDbId docRef) throws IOException
    {
        if (repositoryClient == null)
        {
            LOGGER.warn("updateContent: repositoryClient is null — cannot fetch content for DBID={}", docRef.dbId);
            return;
        }

        // 1. Fetch node metadata
        NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
        nmdp.setNodeIds(Collections.singletonList(docRef.dbId));
        nmdp.setMaxResults(1);

        List<NodeMetaData> metadatas;
        try
        {
            metadatas = repositoryClient.getNodesMetaData(nmdp);
        }
        catch (Exception e)
        {
            throw new IOException("Failed to fetch metadata for DBID=" + docRef.dbId, e);
        }

        if (metadatas == null || metadatas.isEmpty())
        {
            // Node may have been deleted or is inaccessible from the repository.
            // Mark as irrecoverable (-30) so the ContentTracker stops retrying this DBID.
            LOGGER.warn("No metadata returned for DBID={} — marking as irrecoverable", docRef.dbId);
            markContentIrrecoverable(docRef.tenant, docRef.dbId);
            return;
        }

        NodeMetaData metadata = metadatas.get(0);

        // 2. Rebuild the full document (we need all fields since partial update is not possible)
        Node node = new Node();
        node.setId(metadata.getId());
        node.setTxnId(metadata.getTxnId());
        node.setStatus(Node.SolrApiNodeStatus.UPDATED);

        SolrInputDocument doc = documentMapper.toNodeDoc(node, metadata);

        // 3. Fetch and add content for each content property
        Map<QName, PropertyValue> properties = metadata.getProperties();
        boolean contentExtracted = false;
        if (properties != null)
        {
            for (Map.Entry<QName, PropertyValue> entry : properties.entrySet())
            {
                if (entry.getValue() instanceof ContentPropertyValue)
                {
                    QName propQName = entry.getKey();
                    boolean ok = fetchAndAddContent(doc, docRef.dbId, propQName);
                    if (ok)
                    {
                        contentExtracted = true;
                    }
                }
            }
        }

        // 4. Mark as synchronized
        doc.setField(SolrDocumentMapper.FIELD_LAST_INCOMING_CONTENT_VERSION_ID,
                SolrDocumentMapper.CONTENT_UPDATED_MARKER);
        doc.setField(SolrDocumentMapper.FIELD_FTSSTATUS, "Clean");

        // 5. Send to Solr
        addDocument(doc);

        if (contentExtracted)
        {
            LOGGER.debug("Content extracted and indexed for DBID={}", docRef.dbId);
        }
        else
        {
            LOGGER.debug("No extractable content for DBID={}, marked as Clean", docRef.dbId);
        }
    }

    /**
     * Fetches text content for a single content property from the repository
     * and adds the corresponding Solr fields to the document.
     *
     * @return true if text content was successfully extracted
     */
    private boolean fetchAndAddContent(SolrInputDocument doc, long dbId, QName propQName) throws IOException
    {
        String qnameSuffix = propQName.toString();

        try (GetTextContentResponse response = repositoryClient.getTextContent(dbId, propQName, null))
        {
            SolrApiContentStatus status = response.getStatus();

            // Transform status fields
            doc.setField("content@s__tr_status@" + qnameSuffix, status.name());
            if (response.getTransformException() != null)
            {
                doc.setField("content@s__tr_ex@" + qnameSuffix, response.getTransformException());
            }
            if (response.getTransformDuration() != null)
            {
                doc.setField("content@s__tr_time@" + qnameSuffix, response.getTransformDuration());
            }

            if (status != SolrApiContentStatus.OK)
            {
                LOGGER.debug("Content status for DBID={} prop={}: {}", dbId, propQName, status);
                return false;
            }

            // Read the content stream
            InputStream contentStream = response.getContent();
            if (contentStream == null)
            {
                return false;
            }

            // Handle gzip decompression
            String encoding = response.getContentEncoding();
            if ("gzip".equalsIgnoreCase(encoding))
            {
                contentStream = new GZIPInputStream(contentStream);
            }

            String textContent = readContentStream(contentStream);
            if (textContent != null && !textContent.isEmpty())
            {
                // Format: \u0000locale\u0000text — locale is empty (repository-side transform decides)
                doc.addField("content@s__lt@" + qnameSuffix, "\u0000" + "\u0000" + textContent);
                return true;
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to fetch content for DBID={} prop={}: {}", dbId, propQName, e.getMessage());
            doc.setField("content@s__tr_status@" + qnameSuffix, "TRANSFORM_FAILED");
            doc.setField("content@s__tr_ex@" + qnameSuffix, e.getMessage());
        }

        return false;
    }

    /**
     * Reads a content stream into a String, respecting the content stream limit.
     */
    private String readContentStream(InputStream stream) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            char[] buffer = new char[8192];
            long totalRead = 0;
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1)
            {
                totalRead += charsRead;
                if (totalRead > contentStreamLimit)
                {
                    sb.append(buffer, 0, (int) (charsRead - (totalRead - contentStreamLimit)));
                    LOGGER.debug("Content stream limit reached ({} bytes)", contentStreamLimit);
                    break;
                }
                sb.append(buffer, 0, charsRead);
            }
        }
        return sb.toString();
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
    // Tracker state
    // =========================================================================

    /**
     * Writes the tracker state document so that the ConsistencyComponent
     * can read {@code lastTxIdOnServer} from the index and compute {@code txRemaining}.
     */
    public void updateTrackerState(long lastTxIdOnServer, long lastTxCommitTimeOnServer) throws IOException
    {
        SolrInputDocument doc = documentMapper.toTrackerStateDoc(lastTxIdOnServer, lastTxCommitTimeOnServer);
        addDocument(doc);
    }

    /**
     * Marks a document as irrecoverable for content extraction (-30).
     * This is used when the repository returns no metadata for a DBID,
     * typically because the node has been deleted or is otherwise inaccessible.
     * The irrecoverable marker ensures the ContentTracker stops retrying this
     * document, while remaining distinguishable from genuinely up-to-date
     * content (-20/Clean).
     */
    private void markContentIrrecoverable(String tenant, long dbId) throws IOException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField(SolrDocumentMapper.FIELD_SOLR4_ID,
                SolrDocumentMapper.getNodeDocumentId(tenant != null ? tenant : "", dbId));
        doc.setField(SolrDocumentMapper.FIELD_LAST_INCOMING_CONTENT_VERSION_ID,
                Collections.singletonMap("set", SolrDocumentMapper.CONTENT_IRRECOVERABLE_MARKER));
        doc.setField(SolrDocumentMapper.FIELD_FTSSTATUS,
                Collections.singletonMap("set", "Clean"));
        addDocument(doc);
    }

    // =========================================================================
    // Indexing error markers
    // =========================================================================

    /**
     * Marks a node with HAS_INDEXING_ERROR:true via atomic update.
     */
    public void markIndexingError(long dbId, String tenant) throws IOException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField(SolrDocumentMapper.FIELD_SOLR4_ID,
                SolrDocumentMapper.getNodeDocumentId(tenant != null ? tenant : "", dbId));
        doc.setField("HAS_INDEXING_ERROR",
                Collections.singletonMap("set", "true"));
        addDocument(doc);
    }

    /**
     * Clears HAS_INDEXING_ERROR on a node via atomic update.
     */
    public void clearIndexingError(long dbId, String tenant) throws IOException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField(SolrDocumentMapper.FIELD_SOLR4_ID,
                SolrDocumentMapper.getNodeDocumentId(tenant != null ? tenant : "", dbId));
        doc.setField("HAS_INDEXING_ERROR",
                Collections.singletonMap("set", "false"));
        addDocument(doc);
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
