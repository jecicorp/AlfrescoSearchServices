/*
 * Copyright 2026 - Jeci SARL - https://jeci.fr
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * http://www.gnu.org/licenses/.
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

    // =========================================================================
    // SITE and TAG field extraction
    // =========================================================================

    @Test
    public void testSiteFieldExtractedFromPath()
    {
        Node node = new Node();
        node.setId(800L);
        node.setTxnId(70L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(800L);
        metadata.setTxnId(70L);
        metadata.setAclId(600L);
        metadata.setTenantDomain("");
        metadata.setPaths(Collections.singletonList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/site/1.0}sites"
                        + "/{http://www.alfresco.org/model/content/1.0}mysite"
                        + "/{http://www.alfresco.org/model/content/1.0}documentLibrary"
                        + "/{http://www.alfresco.org/model/content/1.0}file.txt", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull("SITE field must be set", siteValues);
        assertEquals(1, siteValues.size());
        assertTrue(siteValues.contains("mysite"));
    }

    @Test
    public void testSharedFilesPath()
    {
        Node node = new Node();
        node.setId(801L);
        node.setTxnId(71L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(801L);
        metadata.setTxnId(71L);
        metadata.setAclId(601L);
        metadata.setTenantDomain("");
        metadata.setPaths(Collections.singletonList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/application/1.0}shared"
                        + "/{http://www.alfresco.org/model/content/1.0}doc.txt", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull("SITE field must be set for shared files", siteValues);
        assertTrue(siteValues.contains("_SHARED_FILES_"));
    }

    @Test
    public void testRepositoryNodeNoSite()
    {
        Node node = new Node();
        node.setId(802L);
        node.setTxnId(72L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(802L);
        metadata.setTxnId(72L);
        metadata.setAclId(602L);
        metadata.setTenantDomain("");
        metadata.setPaths(Collections.singletonList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/content/1.0}myFolder", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull("SITE field must be set to _REPOSITORY_", siteValues);
        assertTrue(siteValues.contains("_REPOSITORY_"));
    }

    // =========================================================================
    // APATH and ANAME ancestor path fields
    // =========================================================================

    @Test
    public void testAncestorPathsProduceApathAndAname()
    {
        Node node = new Node();
        node.setId(810L);
        node.setTxnId(74L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(810L);
        metadata.setTxnId(74L);
        metadata.setAclId(610L);
        metadata.setTenantDomain("");
        metadata.setAncestorPaths(Collections.singletonList("/uuid-a/uuid-b/uuid-c"));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> apathValues = doc.getFieldValues("APATH");
        assertNotNull("APATH field must be set from ancestorPaths", apathValues);
        assertTrue(apathValues.contains("0/uuid-a"));
        assertTrue(apathValues.contains("1/uuid-a/uuid-b"));
        assertTrue(apathValues.contains("2/uuid-a/uuid-b/uuid-c"));
        assertTrue(apathValues.contains("F/uuid-a/uuid-b/uuid-c"));
        assertEquals(4, apathValues.size());

        Collection<Object> anameValues = doc.getFieldValues("ANAME");
        assertNotNull("ANAME field must be set from ancestorPaths", anameValues);
        assertTrue(anameValues.contains("0/uuid-c"));
        assertTrue(anameValues.contains("1/uuid-b/uuid-c"));
        assertTrue(anameValues.contains("2/uuid-a/uuid-b/uuid-c"));
        assertTrue(anameValues.contains("F/uuid-a/uuid-b/uuid-c"));
        assertEquals(4, anameValues.size());
    }

    @Test
    public void testAncestorPathsDeduplicateSharedPrefixes()
    {
        Node node = new Node();
        node.setId(811L);
        node.setTxnId(75L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(811L);
        metadata.setTxnId(75L);
        metadata.setAclId(611L);
        metadata.setTenantDomain("");
        metadata.setAncestorPaths(Arrays.asList("/uuid-a/uuid-b", "/uuid-a/uuid-x"));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> apathValues = doc.getFieldValues("APATH");
        assertNotNull(apathValues);
        // "0/uuid-a" shared by both paths must appear only once;
        // each full path also adds its own "F/..." value
        assertEquals(1, apathValues.stream().filter("0/uuid-a"::equals).count());
        assertTrue(apathValues.contains("1/uuid-a/uuid-b"));
        assertTrue(apathValues.contains("1/uuid-a/uuid-x"));
        assertTrue(apathValues.contains("F/uuid-a/uuid-b"));
        assertTrue(apathValues.contains("F/uuid-a/uuid-x"));
    }

    @Test
    public void testEmptyAncestorPathProducesRootBucket()
    {
        // The store root node has apath="" in the repository response; upstream
        // indexed it as "0/" and "F/" (the level-0 facet bucket for the root).
        Node node = new Node();
        node.setId(813L);
        node.setTxnId(77L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(813L);
        metadata.setTxnId(77L);
        metadata.setAclId(613L);
        metadata.setTenantDomain("");
        metadata.setAncestorPaths(Collections.singletonList(""));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> apathValues = doc.getFieldValues("APATH");
        assertNotNull("APATH must be set for empty ancestor path (store root)", apathValues);
        assertTrue(apathValues.contains("0/"));
        assertTrue(apathValues.contains("F/"));

        Collection<Object> anameValues = doc.getFieldValues("ANAME");
        assertNotNull(anameValues);
        assertTrue(anameValues.contains("0/"));
        assertTrue(anameValues.contains("F/"));
    }

    @Test
    public void testNullAncestorPathsProduceNoApath()
    {
        Node node = new Node();
        node.setId(812L);
        node.setTxnId(76L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(812L);
        metadata.setTxnId(76L);
        metadata.setAclId(612L);
        metadata.setTenantDomain("");
        metadata.setAncestorPaths(null);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        assertNull("APATH must be absent when ancestorPaths is null", doc.getFieldValues("APATH"));
        assertNull("ANAME must be absent when ancestorPaths is null", doc.getFieldValues("ANAME"));
    }

    @Test
    public void testNullOrEmptyPaths()
    {
        Node node = new Node();
        node.setId(803L);
        node.setTxnId(73L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(803L);
        metadata.setTxnId(73L);
        metadata.setAclId(603L);
        metadata.setTenantDomain("");
        metadata.setPaths(null);

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull("SITE field must be _REPOSITORY_ when paths is null", siteValues);
        assertTrue(siteValues.contains("_REPOSITORY_"));
    }

    @Test
    public void testTagFieldExtractedFromPath()
    {
        Node node = new Node();
        node.setId(804L);
        node.setTxnId(74L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(804L);
        metadata.setTxnId(74L);
        metadata.setAclId(604L);
        metadata.setTenantDomain("");
        metadata.setPaths(Arrays.asList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/site/1.0}sites"
                        + "/{http://www.alfresco.org/model/content/1.0}mysite"
                        + "/{http://www.alfresco.org/model/content/1.0}documentLibrary"
                        + "/{http://www.alfresco.org/model/content/1.0}file.txt", null),
                new Pair<>("/{http://www.alfresco.org/model/content/1.0}taggable"
                        + "/{http://www.alfresco.org/model/content/1.0}mytag"
                        + "/{}member", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> tagValues = doc.getFieldValues(FIELD_TAG);
        assertNotNull("TAG field must be set", tagValues);
        assertTrue(tagValues.contains("mytag"));

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull(siteValues);
        assertTrue(siteValues.contains("mysite"));
    }

    @Test
    public void testMultiplePathsMultipleSites()
    {
        Node node = new Node();
        node.setId(805L);
        node.setTxnId(75L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(805L);
        metadata.setTxnId(75L);
        metadata.setAclId(605L);
        metadata.setTenantDomain("");
        metadata.setPaths(Arrays.asList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/site/1.0}sites"
                        + "/{http://www.alfresco.org/model/content/1.0}siteA"
                        + "/{http://www.alfresco.org/model/content/1.0}documentLibrary"
                        + "/{http://www.alfresco.org/model/content/1.0}file.txt", null),
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/site/1.0}sites"
                        + "/{http://www.alfresco.org/model/content/1.0}siteB"
                        + "/{http://www.alfresco.org/model/content/1.0}documentLibrary"
                        + "/{http://www.alfresco.org/model/content/1.0}file.txt", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull(siteValues);
        assertEquals(2, siteValues.size());
        assertTrue(siteValues.contains("siteA"));
        assertTrue(siteValues.contains("siteB"));
    }

    @Test
    public void testISO9075DecodedSiteName()
    {
        Node node = new Node();
        node.setId(806L);
        node.setTxnId(76L);

        NodeMetaData metadata = new NodeMetaData();
        metadata.setId(806L);
        metadata.setTxnId(76L);
        metadata.setAclId(606L);
        metadata.setTenantDomain("");
        metadata.setPaths(Collections.singletonList(
                new Pair<>("/{http://www.alfresco.org/model/application/1.0}company_home"
                        + "/{http://www.alfresco.org/model/site/1.0}sites"
                        + "/{http://www.alfresco.org/model/content/1.0}my_x0020_site"
                        + "/{http://www.alfresco.org/model/content/1.0}documentLibrary", null)));

        SolrInputDocument doc = mapper.toNodeDoc(node, metadata);

        Collection<Object> siteValues = doc.getFieldValues(FIELD_SITE);
        assertNotNull(siteValues);
        assertTrue("Site name should be ISO9075-decoded", siteValues.contains("my site"));
    }
}
