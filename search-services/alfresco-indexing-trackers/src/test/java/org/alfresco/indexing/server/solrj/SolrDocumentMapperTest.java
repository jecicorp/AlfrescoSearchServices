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

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.Transaction;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;

public class SolrDocumentMapperTest
{
    private SolrDocumentMapper mapper;

    @Before
    public void setUp()
    {
        mapper = new SolrDocumentMapper();
    }

    // =========================================================================
    // Transaction document
    // =========================================================================

    @Test
    public void testToTransactionDoc()
    {
        Transaction txn = new Transaction();
        txn.setId(42L);
        txn.setCommitTimeMs(1609459200000L);

        SolrInputDocument doc = mapper.toTransactionDoc(txn);

        // ID format: TRACKER!TX!<encoded>
        String id = (String) doc.getFieldValue(FIELD_SOLR4_ID);
        assertNotNull("id must not be null", id);
        assertTrue("id should start with TRACKER!TX!", id.startsWith("TRACKER!TX!"));

        assertEquals(DOC_TYPE_TX, doc.getFieldValue(FIELD_DOC_TYPE));
        assertEquals(42L, doc.getFieldValue(FIELD_TXID));
        assertEquals(42L, doc.getFieldValue(FIELD_INTXID));
        assertEquals(1609459200000L, doc.getFieldValue(FIELD_TXCOMMITTIME));
        assertEquals(42L, doc.getFieldValue(FIELD_S_TXID));
        assertEquals(1609459200000L, doc.getFieldValue(FIELD_S_TXCOMMITTIME));

        // cascade flag not set by default
        assertNull("CASCADE_FLAG should be null when cascade tracking disabled",
                   doc.getFieldValue(FIELD_CASCADE_FLAG));
    }

    @Test
    public void testToTransactionDocWithCascadeTracking()
    {
        SolrDocumentMapper cascadeMapper = new SolrDocumentMapper(true);
        Transaction txn = new Transaction();
        txn.setId(99L);
        txn.setCommitTimeMs(1000L);

        SolrInputDocument doc = cascadeMapper.toTransactionDoc(txn);
        assertEquals(1, doc.getFieldValue(FIELD_CASCADE_FLAG));
    }

    // =========================================================================
    // ACL ChangeSet document
    // =========================================================================

    @Test
    public void testToAclChangeSetDoc()
    {
        AclChangeSet changeSet = new AclChangeSet(100L, 1609459200000L, 5);

        SolrInputDocument doc = mapper.toAclChangeSetDoc(changeSet);

        String id = (String) doc.getFieldValue(FIELD_SOLR4_ID);
        assertNotNull("id must not be null", id);
        assertTrue("id should start with TRACKER!CHANGE_SET!", id.startsWith("TRACKER!CHANGE_SET!"));

        assertEquals(DOC_TYPE_ACL_TX, doc.getFieldValue(FIELD_DOC_TYPE));
        assertEquals(100L, doc.getFieldValue(FIELD_ACLTXID));
        assertEquals(100L, doc.getFieldValue(FIELD_INACLTXID));
        assertEquals(1609459200000L, doc.getFieldValue(FIELD_ACLTXCOMMITTIME));
    }

    // =========================================================================
    // ACL document
    // =========================================================================

    @Test
    public void testToAclDocs()
    {
        List<String> readers = Arrays.asList("user1", "GROUP_ALFRESCO_ADMINISTRATORS");
        List<String> denied = Collections.singletonList("user2");

        AclReaders aclReaders = new AclReaders(200L, readers, denied, 50L, "");

        List<SolrInputDocument> docs = mapper.toAclDocs(Collections.singletonList(aclReaders));

        assertEquals(1, docs.size());
        SolrInputDocument doc = docs.get(0);

        String id = (String) doc.getFieldValue(FIELD_SOLR4_ID);
        assertNotNull("id must not be null", id);
        assertTrue("id should end with !ACL", id.endsWith("!ACL"));

        assertEquals(DOC_TYPE_ACL, doc.getFieldValue(FIELD_DOC_TYPE));
        assertEquals(200L, doc.getFieldValue(FIELD_ACLID));
        assertEquals(50L, doc.getFieldValue(FIELD_INACLTXID));

        // Check readers
        Collection<Object> readerValues = doc.getFieldValues(FIELD_READER);
        assertNotNull("readers must not be null", readerValues);
        assertEquals(2, readerValues.size());
        assertTrue(readerValues.contains("user1"));
        assertTrue(readerValues.contains("GROUP_ALFRESCO_ADMINISTRATORS"));

        // Check denied
        Collection<Object> deniedValues = doc.getFieldValues(FIELD_DENIED);
        assertNotNull("denied must not be null", deniedValues);
        assertEquals(1, deniedValues.size());
        assertTrue(deniedValues.contains("user2"));
    }

    @Test
    public void testToAclDocsWithTenant()
    {
        List<String> readers = Arrays.asList("user1", "GROUP_EVERYONE");
        List<String> denied = Collections.emptyList();

        AclReaders aclReaders = new AclReaders(201L, readers, denied, 51L, "tenantA");

        List<SolrInputDocument> docs = mapper.toAclDocs(Collections.singletonList(aclReaders));

        assertEquals(1, docs.size());
        SolrInputDocument doc = docs.get(0);

        // GROUP_EVERYONE should get tenant suffix, user1 should not
        Collection<Object> readerValues = doc.getFieldValues(FIELD_READER);
        assertTrue("user1 should NOT have tenant appended", readerValues.contains("user1"));
        assertTrue("GROUP_EVERYONE should have tenant appended", readerValues.contains("GROUP_EVERYONE@tenantA"));
    }

    @Test
    public void testToAclDocsWithNullList()
    {
        List<SolrInputDocument> docs = mapper.toAclDocs(null);
        assertNotNull(docs);
        assertTrue(docs.isEmpty());
    }

    @Test
    public void testToAclDocsMultipleEntries()
    {
        AclReaders acl1 = new AclReaders(1L, Collections.singletonList("user1"), Collections.emptyList(), 10L, "");
        AclReaders acl2 = new AclReaders(2L, Collections.singletonList("user2"), Collections.emptyList(), 10L, "");

        List<SolrInputDocument> docs = mapper.toAclDocs(Arrays.asList(acl1, acl2));
        assertEquals(2, docs.size());
    }

    // =========================================================================
    // Node document
    // =========================================================================

    @Test
    public void testToNodeDoc()
    {
        Node node = new Node();
        node.setId(500L);
        node.setTxnId(42L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(500L);
        metadata.setTxnId(42L);
        metadata.setAclId(300L);
        metadata.setTenantDomain("");
        metadata.setNodeRef(new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "test-uuid"));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        String id = (String) doc.getFieldValue(FIELD_SOLR4_ID);
        assertNotNull("id must not be null", id);
        // Default tenant should produce _DEFAULT_!<encoded>
        assertTrue("id should start with _DEFAULT_!", id.startsWith("_DEFAULT_!"));

        assertEquals(DOC_TYPE_NODE, doc.getFieldValue(FIELD_DOC_TYPE));
        assertEquals(500L, doc.getFieldValue(FIELD_DBID));
        assertEquals(42L, doc.getFieldValue(FIELD_INTXID));
        assertEquals(300L, doc.getFieldValue(FIELD_ACLID));
        assertEquals("workspace://SpacesStore/test-uuid", doc.getFieldValue(FIELD_LID));
    }

    @Test
    public void testToNodeDocWithTenant()
    {
        Node node = new Node();
        node.setId(501L);
        node.setTxnId(43L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(501L);
        metadata.setTxnId(43L);
        metadata.setAclId(301L);
        metadata.setTenantDomain("mycompany.com");
        metadata.setNodeRef(new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "uuid-2"));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        String id = (String) doc.getFieldValue(FIELD_SOLR4_ID);
        assertTrue("id should start with tenant", id.startsWith("mycompany.com!"));
    }

    // =========================================================================
    // ID generation methods
    // =========================================================================

    @Test
    public void testTransactionDocumentIdFormat()
    {
        String id = SolrDocumentMapper.getTransactionDocumentId(42L);
        assertTrue(id.startsWith("TRACKER!TX!"));
    }

    @Test
    public void testAclChangeSetDocumentIdFormat()
    {
        String id = SolrDocumentMapper.getAclChangeSetDocumentId(100L);
        assertTrue(id.startsWith("TRACKER!CHANGE_SET!"));
    }

    @Test
    public void testAclDocumentIdFormat()
    {
        String id = SolrDocumentMapper.getAclDocumentId("", 200L);
        assertTrue(id.startsWith("_DEFAULT_!"));
        assertTrue(id.endsWith("!ACL"));
    }

    @Test
    public void testNodeDocumentIdFormat()
    {
        String id = SolrDocumentMapper.getNodeDocumentId("", 500L);
        assertTrue(id.startsWith("_DEFAULT_!"));
    }

    @Test
    public void testNodeDocumentIdWithTenant()
    {
        String id = SolrDocumentMapper.getNodeDocumentId("tenant.com", 500L);
        assertTrue(id.startsWith("tenant.com!"));
    }
}
