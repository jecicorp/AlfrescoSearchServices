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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.ContentPropertyValue;
import org.alfresco.solr.client.MLTextPropertyValue;
import org.alfresco.solr.client.MultiPropertyValue;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.StringPropertyValue;
import org.alfresco.solr.client.Transaction;
import org.alfresco.util.Pair;
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

    @Test
    public void testToNodeDocWithFullMetadata()
    {
        Node node = new Node();
        node.setId(600L);
        node.setTxnId(50L);

        QName contentType = QName.createQName("{http://www.alfresco.org/model/content/1.0}person");
        QName titledAspect = QName.createQName("{http://www.alfresco.org/model/content/1.0}titled");

        NodeRef parentRef = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "parent-uuid");
        NodeRef nodeRef = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "node-uuid");
        NodeRef ancestorRef = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, "ancestor-uuid");

        ChildAssociationRef primaryAssoc = new ChildAssociationRef(
                QName.createQName("{http://www.alfresco.org/model/content/1.0}contains"),
                parentRef, QName.createQName("{http://www.alfresco.org/model/content/1.0}myChild"),
                nodeRef, true, -1);

        QName userNameProp = QName.createQName("{http://www.alfresco.org/model/content/1.0}userName");

        Map<QName, PropertyValue> properties = new HashMap<>();
        properties.put(userNameProp, new StringPropertyValue("admin"));

        Set<QName> aspects = new HashSet<>();
        aspects.add(titledAspect);

        Set<NodeRef> ancestors = new HashSet<>();
        ancestors.add(ancestorRef);

        List<Pair<String, QName>> paths = Collections.singletonList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home", null));

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(600L);
        metadata.setTxnId(50L);
        metadata.setAclId(400L);
        metadata.setTenantDomain("");
        metadata.setNodeRef(nodeRef);
        metadata.setType(contentType);
        metadata.setOwner("admin");
        metadata.setAspects(aspects);
        metadata.setAncestors(ancestors);
        metadata.setParentAssocs(Collections.singletonList(primaryAssoc));
        metadata.setPaths(paths);
        metadata.setProperties(properties);
        metadata.setParentAssocsCrc(12345L);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        // Type
        assertEquals(contentType.toString(), doc.getFieldValue(FIELD_TYPE));

        // ISNODE
        assertEquals("T", doc.getFieldValue(FIELD_ISNODE));

        // Tenant
        assertEquals("_DEFAULT_", doc.getFieldValue(FIELD_TENANT));

        // Owner
        assertEquals("admin", doc.getFieldValue(FIELD_OWNER));

        // Aspects
        Collection<Object> aspectValues = doc.getFieldValues(FIELD_ASPECT);
        assertNotNull(aspectValues);
        assertTrue(aspectValues.contains(titledAspect.toString()));

        // Paths
        Collection<Object> pathValues = doc.getFieldValues(FIELD_PATH);
        assertNotNull(pathValues);
        assertEquals(1, pathValues.size());

        // Ancestors
        Collection<Object> ancestorValues = doc.getFieldValues(FIELD_ANCESTOR);
        assertNotNull(ancestorValues);
        assertTrue(ancestorValues.contains(ancestorRef.toString()));

        // Parent associations
        Collection<Object> parentValues = doc.getFieldValues(FIELD_PARENT);
        assertNotNull(parentValues);
        assertTrue(parentValues.contains(parentRef.toString()));
        assertEquals(parentRef.toString(), doc.getFieldValue(FIELD_PRIMARYPARENT));
        assertEquals(12345L, doc.getFieldValue(FIELD_PARENT_ASSOC_CRC));

        // Properties — indexed with Solr dynamic field prefix
        String expectedSolrField = "text@s__lt@" + userNameProp.toString();
        assertEquals("admin", doc.getFieldValue(expectedSolrField));
        Collection<Object> propIndex = doc.getFieldValues(FIELD_PROPERTIES);
        assertNotNull(propIndex);
        assertTrue(propIndex.contains(userNameProp.toString()));
    }

    @Test
    public void testToNodeDocWithMLTextProperty()
    {
        Node node = new Node();
        node.setId(601L);
        node.setTxnId(51L);

        QName titleProp = QName.createQName("{http://www.alfresco.org/model/content/1.0}title");
        MLTextPropertyValue mlText = new MLTextPropertyValue();
        mlText.addValue(Locale.ENGLISH, "My Title");
        mlText.addValue(Locale.FRENCH, "Mon Titre");

        Map<QName, PropertyValue> properties = new HashMap<>();
        properties.put(titleProp, mlText);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(601L);
        metadata.setTxnId(51L);
        metadata.setAclId(401L);
        metadata.setTenantDomain("");
        metadata.setProperties(properties);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        String solrField = "mltext@m__lt@" + titleProp.toString();
        Collection<Object> titleValues = doc.getFieldValues(solrField);
        assertNotNull(titleValues);
        assertEquals(2, titleValues.size());
    }

    @Test
    public void testToNodeDocWithNullProperty()
    {
        Node node = new Node();
        node.setId(602L);
        node.setTxnId(52L);

        QName descProp = QName.createQName("{http://www.alfresco.org/model/content/1.0}description");

        Map<QName, PropertyValue> properties = new HashMap<>();
        properties.put(descProp, null);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(602L);
        metadata.setTxnId(52L);
        metadata.setAclId(402L);
        metadata.setTenantDomain("");
        metadata.setProperties(properties);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        // Null property should be in NULLPROPERTIES
        Collection<Object> nullProps = doc.getFieldValues(FIELD_NULLPROPERTIES);
        assertNotNull(nullProps);
        assertTrue(nullProps.contains(descProp.toString()));

        // Should NOT be in PROPERTIES
        Collection<Object> propIndex = doc.getFieldValues(FIELD_PROPERTIES);
        assertNull(propIndex);
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

    // =========================================================================
    // Content property metadata — must not be duplicated
    // =========================================================================

    @Test
    public void testToNodeDocWithContentProperty_metadataNotDuplicated()
    {
        Node node = new Node();
        node.setId(700L);
        node.setTxnId(60L);

        QName contentProp = QName.createQName("{http://www.alfresco.org/model/content/1.0}content");
        ContentPropertyValue content = new ContentPropertyValue(Locale.ENGLISH, 1024L, "UTF-8", "text/plain", 1L);

        Map<QName, PropertyValue> properties = new HashMap<>();
        properties.put(contentProp, content);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(700L);
        metadata.setTxnId(60L);
        metadata.setAclId(500L);
        metadata.setTenantDomain("");
        metadata.setProperties(properties);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        // Content metadata fields must exist exactly once (not duplicated)
        String localeField = "content@s__locale@" + contentProp.toString();
        String mimetypeField = "content@s__mimetype@" + contentProp.toString();
        String encodingField = "content@s__encoding@" + contentProp.toString();
        String sizeField = "content@s__size@" + contentProp.toString();

        Collection<Object> localeValues = doc.getFieldValues(localeField);
        assertNotNull("locale field must exist", localeValues);
        assertEquals("locale field must have exactly 1 value", 1, localeValues.size());

        Collection<Object> mimetypeValues = doc.getFieldValues(mimetypeField);
        assertNotNull("mimetype field must exist", mimetypeValues);
        assertEquals("mimetype field must have exactly 1 value", 1, mimetypeValues.size());

        Collection<Object> encodingValues = doc.getFieldValues(encodingField);
        assertNotNull("encoding field must exist", encodingValues);
        assertEquals("encoding field must have exactly 1 value", 1, encodingValues.size());

        Collection<Object> sizeValues = doc.getFieldValues(sizeField);
        assertNotNull("size field must exist", sizeValues);
        assertEquals("size field must have exactly 1 value", 1, sizeValues.size());
    }

    @Test
    public void testToNodeDocWithContentProperty_markedDirty()
    {
        Node node = new Node();
        node.setId(701L);
        node.setTxnId(61L);

        QName contentProp = QName.createQName("{http://www.alfresco.org/model/content/1.0}content");
        ContentPropertyValue content = new ContentPropertyValue(Locale.ENGLISH, 512L, "UTF-8", "application/pdf", 2L);

        Map<QName, PropertyValue> properties = new HashMap<>();
        properties.put(contentProp, content);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(701L);
        metadata.setTxnId(61L);
        metadata.setAclId(501L);
        metadata.setTenantDomain("");
        metadata.setProperties(properties);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        // Node with content should be marked as needing content extraction
        assertEquals(CONTENT_OUTDATED_MARKER, doc.getFieldValue(FIELD_LAST_INCOMING_CONTENT_VERSION_ID));
        assertEquals("Dirty", doc.getFieldValue(FIELD_FTSSTATUS));
    }
}
