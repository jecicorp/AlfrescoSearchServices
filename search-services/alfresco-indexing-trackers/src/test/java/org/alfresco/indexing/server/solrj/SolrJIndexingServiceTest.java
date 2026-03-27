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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.indexing.server.solrj.LocalDictionaryService;
import org.alfresco.indexing.server.solrj.SolrJQueryService;
import org.alfresco.repo.dictionary.DictionaryComponent;
import org.alfresco.service.cmr.dictionary.AspectDefinition;
import org.alfresco.service.cmr.dictionary.ChildAssociationDefinition;
import org.alfresco.service.cmr.dictionary.TypeDefinition;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.Transaction;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SolrJIndexingServiceTest
{
    private static final String COLLECTION = "alfresco";

    @Mock
    private SolrClient solrClient;

    @Mock
    private SolrJQueryService queryService;

    @Mock
    private LocalDictionaryService dictionaryService;

    @Mock
    private DictionaryComponent dictionaryComponent;

    @Mock
    private SOLRAPIClient repositoryClient;

    private SolrJIndexingService service;

    private SolrJIndexingService serviceWithDeps;

    @Before
    public void setUp()
    {
        service = new SolrJIndexingService(solrClient, COLLECTION);

        when(dictionaryService.getDictionaryComponent()).thenReturn(dictionaryComponent);
        serviceWithDeps = new SolrJIndexingService(solrClient, COLLECTION,
                new SolrDocumentMapper(), repositoryClient, queryService, dictionaryService);
    }

    // =========================================================================
    // Deletion tests
    // =========================================================================

    @Test
    public void deleteByTransactionId_shouldDeleteByINTXID() throws Exception
    {
        service.deleteByTransactionId(42L);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("INTXID:42", queryCaptor.getValue());
    }

    @Test
    public void deleteByAclChangeSetId_shouldDeleteByINACLTXID() throws Exception
    {
        service.deleteByAclChangeSetId(99L);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("INACLTXID:99", queryCaptor.getValue());
    }

    @Test
    public void deleteByAclId_shouldDeleteByACLID() throws Exception
    {
        service.deleteByAclId(7L);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("ACLID:7", queryCaptor.getValue());
    }

    @Test
    public void deleteByNodeId_shouldDeleteByDBID() throws Exception
    {
        service.deleteByNodeId(123L);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("DBID:123", queryCaptor.getValue());
    }

    @Test
    public void maintainCap_shouldDeleteByRangeQuery() throws Exception
    {
        service.maintainCap(500L);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("DBID:{500 TO *}", queryCaptor.getValue());
    }

    @Test
    public void deleteByTransactionId_shouldWrapSolrServerException() throws Exception
    {
        when(solrClient.deleteByQuery(eq(COLLECTION), any(String.class)))
                .thenThrow(new SolrServerException("connection refused"));

        assertThrows(IOException.class, () -> service.deleteByTransactionId(1L));
    }

    // =========================================================================
    // Indexing tests — Transaction
    // =========================================================================

    @Test
    public void indexTransaction_shouldAddDocumentToSolr() throws Exception
    {
        Transaction txn = new Transaction();
        txn.setId(10L);
        txn.setCommitTimeMs(1234567890L);

        service.indexTransaction(txn, true);

        ArgumentCaptor<SolrInputDocument> docCaptor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq(COLLECTION), docCaptor.capture());

        SolrInputDocument doc = docCaptor.getValue();
        assertEquals(10L, doc.getFieldValue("TXID"));
        assertEquals(10L, doc.getFieldValue("INTXID"));
        assertEquals(1234567890L, doc.getFieldValue("TXCOMMITTIME"));
        assertEquals("Tx", doc.getFieldValue("DOC_TYPE"));
    }

    // =========================================================================
    // Indexing tests — AclChangeSet
    // =========================================================================

    @Test
    public void indexAclTransaction_shouldAddDocumentToSolr() throws Exception
    {
        AclChangeSet changeSet = new AclChangeSet(20L, 9876543210L, 5);

        service.indexAclTransaction(changeSet, true);

        ArgumentCaptor<SolrInputDocument> docCaptor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq(COLLECTION), docCaptor.capture());

        SolrInputDocument doc = docCaptor.getValue();
        assertEquals(20L, doc.getFieldValue("ACLTXID"));
        assertEquals(20L, doc.getFieldValue("INACLTXID"));
        assertEquals(9876543210L, doc.getFieldValue("ACLTXCOMMITTIME"));
        assertEquals("AclTx", doc.getFieldValue("DOC_TYPE"));
    }

    // =========================================================================
    // Indexing tests — ACL
    // =========================================================================

    @Test
    public void indexAcl_shouldAddDocumentsToSolr() throws Exception
    {
        AclReaders aclReaders = new AclReaders(30L,
                Arrays.asList("user1", "GROUP_EVERYONE"),
                Collections.emptyList(),
                5L, "");

        long elapsed = service.indexAcl(Collections.singletonList(aclReaders), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SolrInputDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(eq(COLLECTION), docsCaptor.capture());

        List<SolrInputDocument> docs = docsCaptor.getValue();
        assertEquals(1, docs.size());

        SolrInputDocument doc = docs.get(0);
        assertEquals(30L, doc.getFieldValue("ACLID"));
        assertEquals(5L, doc.getFieldValue("INACLTXID"));
        assertEquals("Acl", doc.getFieldValue("DOC_TYPE"));
        assertTrue(elapsed > 0);
    }

    @Test
    public void indexAcl_emptyList_shouldNotCallSolr() throws Exception
    {
        service.indexAcl(Collections.emptyList(), true);

        verify(solrClient, never()).add(eq(COLLECTION), anyList());
    }

    // =========================================================================
    // Indexing tests — Node
    // =========================================================================

    @Test
    public void indexNode_deletedStatus_shouldDeleteByNodeId() throws Exception
    {
        Node node = new Node();
        node.setId(100L);
        node.setStatus(SolrApiNodeStatus.DELETED);

        service.indexNode(node, true);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("DBID:100", queryCaptor.getValue());
        verify(solrClient, never()).add(eq(COLLECTION), any(SolrInputDocument.class));
    }

    @Test
    public void indexNode_nonShardDeleted_shouldDeleteByNodeId() throws Exception
    {
        Node node = new Node();
        node.setId(101L);
        node.setStatus(SolrApiNodeStatus.NON_SHARD_DELETED);

        service.indexNode(node, true);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq(COLLECTION), queryCaptor.capture());
        assertEquals("DBID:101", queryCaptor.getValue());
    }

    @Test
    public void indexNode_updatedStatus_shouldNotThrow() throws Exception
    {
        Node node = new Node();
        node.setId(102L);
        node.setStatus(SolrApiNodeStatus.UPDATED);

        // Should not throw; just logs a debug message
        service.indexNode(node, true);

        // No add or delete expected for the simple indexNode(node, overwrite) path
        verify(solrClient, never()).add(eq(COLLECTION), any(SolrInputDocument.class));
        verify(solrClient, never()).deleteByQuery(eq(COLLECTION), any(String.class));
    }

    @Test
    public void indexNodeWithMetadata_shouldAddDocumentToSolr() throws Exception
    {
        Node node = new Node();
        node.setId(200L);
        node.setTxnId(50L);
        node.setStatus(SolrApiNodeStatus.UPDATED);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(200L);
        metadata.setTxnId(50L);
        metadata.setAclId(10L);
        metadata.setTenantDomain("");

        service.indexNode(node, metadata, true);

        ArgumentCaptor<SolrInputDocument> docCaptor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq(COLLECTION), docCaptor.capture());

        SolrInputDocument doc = docCaptor.getValue();
        assertEquals(200L, doc.getFieldValue("DBID"));
        assertEquals(50L, doc.getFieldValue("INTXID"));
        assertEquals(10L, doc.getFieldValue("ACLID"));
        assertEquals("Node", doc.getFieldValue("DOC_TYPE"));
    }

    @Test
    public void indexNodeWithMetadata_deletedStatus_shouldDeleteNotAdd() throws Exception
    {
        Node node = new Node();
        node.setId(201L);
        node.setStatus(SolrApiNodeStatus.DELETED);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(201L);

        service.indexNode(node, metadata, true);

        verify(solrClient).deleteByQuery(eq(COLLECTION), eq("DBID:201"));
        verify(solrClient, never()).add(eq(COLLECTION), any(SolrInputDocument.class));
    }

    // =========================================================================
    // Indexing tests — indexNodes (batch)
    // =========================================================================

    @Test
    public void indexNodes_shouldDelegateToIndexNodeForEach() throws Exception
    {
        Node deleted = new Node();
        deleted.setId(300L);
        deleted.setStatus(SolrApiNodeStatus.DELETED);

        Node updated = new Node();
        updated.setId(301L);
        updated.setStatus(SolrApiNodeStatus.UPDATED);

        service.indexNodes(Arrays.asList(deleted, updated), true);

        // The deleted node triggers a deleteByQuery
        verify(solrClient).deleteByQuery(eq(COLLECTION), eq("DBID:300"));
    }

    // =========================================================================
    // Indexing tests — capIndex
    // =========================================================================

    @Test
    public void capIndex_shouldCreateStateDocumentWithNegativeDbid() throws Exception
    {
        service.capIndex(999L);

        ArgumentCaptor<SolrInputDocument> docCaptor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq(COLLECTION), docCaptor.capture());

        SolrInputDocument doc = docCaptor.getValue();
        assertEquals("TRACKER!STATE!CAP", doc.getFieldValue("id"));
        assertEquals(-999L, doc.getFieldValue("DBID"));
        assertEquals("State", doc.getFieldValue("DOC_TYPE"));
        assertEquals(0, doc.getFieldValue("_version_"));
    }

    // =========================================================================
    // Indexing tests — reindexNodeByQuery
    // =========================================================================

    @Test
    public void reindexNodeByQuery_shouldThrowUnsupported() throws Exception
    {
        assertThrows(UnsupportedOperationException.class,
                () -> service.reindexNodeByQuery("TYPE:cm\\:content"));
    }

    // =========================================================================
    // Error wrapping
    // =========================================================================

    @Test
    public void indexTransaction_shouldWrapSolrServerException() throws Exception
    {
        when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class)))
                .thenThrow(new SolrServerException("connection refused"));

        Transaction txn = new Transaction();
        txn.setId(1L);

        assertThrows(IOException.class, () -> service.indexTransaction(txn, true));
    }

    // =========================================================================
    // cascadeNodes / mayHaveChildren tests
    // =========================================================================

    @Test
    public void cascadeNodes_skipsNodeWhoseTypeHasNoChildAssocs() throws Exception
    {
        QName contentType = QName.createQName("http://www.alfresco.org/model/content/1.0", "content");
        TypeDefinition typeDef = mock(TypeDefinition.class);
        when(typeDef.getChildAssociations()).thenReturn(Collections.emptyMap());
        when(dictionaryComponent.getType(contentType)).thenReturn(typeDef);

        NodeMetaData parent = new NodeMetaData();
        parent.setType(contentType);
        parent.setAspects(Collections.emptySet());
        parent.setTxnId(10L);

        serviceWithDeps.cascadeNodes(Collections.singletonList(parent), true);

        verify(queryService, never()).getDescendantNodeIds(any());
    }

    @Test
    public void cascadeNodes_proceedsWhenTypeHasChildAssocs() throws Exception
    {
        QName folderType = QName.createQName("http://www.alfresco.org/model/content/1.0", "folder");
        TypeDefinition typeDef = mock(TypeDefinition.class);
        ChildAssociationDefinition childAssocDef = mock(ChildAssociationDefinition.class);
        Map<QName, ChildAssociationDefinition> childAssocs = Map.of(
                QName.createQName("http://www.alfresco.org/model/content/1.0", "contains"), childAssocDef);
        when(typeDef.getChildAssociations()).thenReturn(childAssocs);
        when(dictionaryComponent.getType(folderType)).thenReturn(typeDef);

        NodeMetaData parent = new NodeMetaData();
        parent.setType(folderType);
        parent.setAspects(Collections.emptySet());
        parent.setTxnId(10L);
        parent.setNodeRef(new org.alfresco.service.cmr.repository.NodeRef("workspace://SpacesStore/parent-uuid"));

        when(queryService.getDescendantNodeIds("workspace://SpacesStore/parent-uuid"))
                .thenReturn(Collections.emptyMap());

        serviceWithDeps.cascadeNodes(Collections.singletonList(parent), true);

        verify(queryService).getDescendantNodeIds("workspace://SpacesStore/parent-uuid");
    }

    @Test
    public void cascadeNodes_proceedsWhenAspectHasChildAssocs() throws Exception
    {
        QName nodeType = QName.createQName("http://www.alfresco.org/model/content/1.0", "content");
        TypeDefinition typeDef = mock(TypeDefinition.class);
        when(typeDef.getChildAssociations()).thenReturn(Collections.emptyMap());
        when(dictionaryComponent.getType(nodeType)).thenReturn(typeDef);

        QName aspectQName = QName.createQName("http://custom", "hasChildren");
        AspectDefinition aspectDef = mock(AspectDefinition.class);
        ChildAssociationDefinition childAssocDef = mock(ChildAssociationDefinition.class);
        when(aspectDef.getChildAssociations()).thenReturn(Map.of(
                QName.createQName("http://custom", "childAssoc"), childAssocDef));
        when(dictionaryComponent.getAspect(aspectQName)).thenReturn(aspectDef);

        NodeMetaData parent = new NodeMetaData();
        parent.setType(nodeType);
        parent.setAspects(Set.of(aspectQName));
        parent.setTxnId(10L);
        parent.setNodeRef(new org.alfresco.service.cmr.repository.NodeRef("workspace://SpacesStore/aspect-uuid"));

        when(queryService.getDescendantNodeIds("workspace://SpacesStore/aspect-uuid"))
                .thenReturn(Collections.emptyMap());

        serviceWithDeps.cascadeNodes(Collections.singletonList(parent), true);

        verify(queryService).getDescendantNodeIds("workspace://SpacesStore/aspect-uuid");
    }
}
