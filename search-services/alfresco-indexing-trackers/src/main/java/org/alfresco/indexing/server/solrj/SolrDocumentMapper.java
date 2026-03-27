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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
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
import org.alfresco.util.NumericEncoder;
import org.alfresco.util.Pair;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Alfresco domain objects (Transaction, AclChangeSet, AclReaders, Node/NodeMetaData)
 * into {@link SolrInputDocument} instances using the exact same field names as
 * {@code SolrInformationServer} produces.
 *
 * <p>Field name constants are duplicated here (from {@code QueryConstants}) to avoid
 * pulling in the full Solr-embedded dependency tree. The string values MUST stay in sync
 * with {@code org.alfresco.repo.search.adaptor.QueryConstants}.</p>
 */
public class SolrDocumentMapper
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrDocumentMapper.class);

    // ---------------------------------------------------------------------------
    // Field name constants — values from org.alfresco.repo.search.adaptor.QueryConstants
    // ---------------------------------------------------------------------------
    public static final String FIELD_SOLR4_ID = "id";
    public static final String FIELD_VERSION = "_version_";
    public static final String FIELD_DOC_TYPE = "DOC_TYPE";
    public static final String FIELD_DBID = "DBID";
    public static final String FIELD_LID = "LID";
    public static final String FIELD_INTXID = "INTXID";
    public static final String FIELD_TXID = "TXID";
    public static final String FIELD_TXCOMMITTIME = "TXCOMMITTIME";
    public static final String FIELD_S_TXID = "S_TXID";
    public static final String FIELD_S_TXCOMMITTIME = "S_TXCOMMITTIME";
    public static final String FIELD_ACLTXID = "ACLTXID";
    public static final String FIELD_INACLTXID = "INACLTXID";
    public static final String FIELD_ACLTXCOMMITTIME = "ACLTXCOMMITTIME";
    public static final String FIELD_ACLID = "ACLID";
    public static final String FIELD_READER = "READER";
    public static final String FIELD_DENIED = "DENIED";
    public static final String FIELD_CASCADE_FLAG = "int@s_@cascade";
    public static final String FIELD_S_INTXID = "S_INTXID";
    public static final String FIELD_S_ACLTXID = "S_ACLTXID";
    public static final String FIELD_S_INACLTXID = "S_INACLTXID";
    public static final String FIELD_S_ACLTXCOMMITTIME = "S_ACLTXCOMMITTIME";

    // Content versioning fields — used by ContentTracker to detect documents needing content extraction
    public static final String FIELD_LAST_INCOMING_CONTENT_VERSION_ID = "LAST_INCOMING_CONTENT_VERSION_ID";
    public static final String FIELD_LATEST_APPLIED_CONTENT_VERSION_ID = "LATEST_APPLIED_CONTENT_VERSION_ID";
    public static final String FIELD_FTSSTATUS = "FTSSTATUS";

    /** Marker value: content is outdated and needs extraction (schema default) */
    public static final long CONTENT_OUTDATED_MARKER = -10;
    /** Marker value: content is up-to-date (no extraction needed) */
    public static final long CONTENT_UPDATED_MARKER = -20;
    /** Marker value: content extraction failed permanently (metadata not available from repository) */
    public static final long CONTENT_IRRECOVERABLE_MARKER = -30;

    // Node metadata fields — from org.alfresco.repo.search.adaptor.QueryConstants
    public static final String FIELD_TYPE = "TYPE";
    public static final String FIELD_ASPECT = "ASPECT";
    public static final String FIELD_ISNODE = "ISNODE";
    public static final String FIELD_TENANT = "TENANT";
    public static final String FIELD_OWNER = "OWNER";
    public static final String FIELD_PATH = "PATH";
    public static final String FIELD_ANCESTOR = "ANCESTOR";
    public static final String FIELD_PARENT = "PARENT";
    public static final String FIELD_PRIMARYPARENT = "PRIMARYPARENT";
    public static final String FIELD_PRIMARYASSOCTYPEQNAME = "PRIMARYASSOCTYPEQNAME";
    public static final String FIELD_PRIMARYASSOCQNAME = "PRIMARYASSOCQNAME";
    public static final String FIELD_PROPERTIES = "PROPERTIES";
    public static final String FIELD_NULLPROPERTIES = "NULLPROPERTIES";
    public static final String FIELD_PARENT_ASSOC_CRC = "PARENTASSOCCRC";

    // ---------------------------------------------------------------------------
    // Document type constants — values from SolrInformationServer
    // ---------------------------------------------------------------------------
    public static final String DOC_TYPE_TX = "Tx";
    public static final String DOC_TYPE_ACL_TX = "AclTx";
    public static final String DOC_TYPE_ACL = "Acl";
    public static final String DOC_TYPE_NODE = "Node";
    public static final String DOC_TYPE_ERROR_NODE = "ErrorNode";

    // ---------------------------------------------------------------------------
    // ID format constants — mirrors AlfrescoSolrDataModel
    // ---------------------------------------------------------------------------
    private static final String DEFAULT_TENANT = "_DEFAULT_";

    private final boolean cascadeTrackingEnabled;
    private final LocalDictionaryService dictionaryService;
    private final PropertyFieldMapper fieldMapper;

    /**
     * Creates a mapper with dictionary-aware property field naming.
     *
     * @param cascadeTrackingEnabled whether to set the cascade flag on transaction documents
     * @param dictionaryService      local dictionary for property definitions (may be null for fallback mode)
     */
    public SolrDocumentMapper(boolean cascadeTrackingEnabled, LocalDictionaryService dictionaryService)
    {
        this.cascadeTrackingEnabled = cascadeTrackingEnabled;
        this.dictionaryService = dictionaryService;
        this.fieldMapper = dictionaryService != null ? new PropertyFieldMapper() : null;
    }

    /**
     * Creates a mapper.
     *
     * @param cascadeTrackingEnabled whether to set the cascade flag on transaction documents
     */
    public SolrDocumentMapper(boolean cascadeTrackingEnabled)
    {
        this(cascadeTrackingEnabled, null);
    }

    /**
     * Creates a mapper with cascade tracking disabled.
     */
    public SolrDocumentMapper()
    {
        this(false, null);
    }

    // =========================================================================
    // Transaction document
    // =========================================================================

    /**
     * Creates a Solr document for a Transaction.
     * Mirrors {@code SolrInformationServer.indexTransaction(Transaction, boolean)}.
     *
     * <p>Document ID format: {@code TRACKER!TX!<encoded-txId>}</p>
     *
     * @param txn the transaction to convert
     * @return a SolrInputDocument ready to be sent to Solr
     */
    public SolrInputDocument toTransactionDoc(Transaction txn)
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(FIELD_SOLR4_ID, getTransactionDocumentId(txn.getId()));
        doc.addField(FIELD_VERSION, 0);
        doc.addField(FIELD_TXID, txn.getId());
        doc.addField(FIELD_INTXID, txn.getId());
        doc.addField(FIELD_TXCOMMITTIME, txn.getCommitTimeMs());
        doc.addField(FIELD_DOC_TYPE, DOC_TYPE_TX);

        // Stored fields for backward compat (ACE-4284)
        doc.addField(FIELD_S_TXID, txn.getId());
        doc.addField(FIELD_S_TXCOMMITTIME, txn.getCommitTimeMs());

        if (cascadeTrackingEnabled)
        {
            doc.addField(FIELD_CASCADE_FLAG, 1);
        }

        return doc;
    }

    // =========================================================================
    // ACL ChangeSet (ACL Transaction) document
    // =========================================================================

    /**
     * Creates a Solr document for an ACL ChangeSet.
     * Mirrors {@code SolrInformationServer.indexAclTransaction(AclChangeSet, boolean)}.
     *
     * <p>Document ID format: {@code TRACKER!CHANGE_SET!<encoded-changeSetId>}</p>
     *
     * @param changeSet the ACL change set to convert
     * @return a SolrInputDocument ready to be sent to Solr
     */
    public SolrInputDocument toAclChangeSetDoc(AclChangeSet changeSet)
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(FIELD_SOLR4_ID, getAclChangeSetDocumentId(changeSet.getId()));
        doc.addField(FIELD_VERSION, "0");
        doc.addField(FIELD_ACLTXID, changeSet.getId());
        doc.addField(FIELD_INACLTXID, changeSet.getId());
        doc.addField(FIELD_ACLTXCOMMITTIME, changeSet.getCommitTimeMs());
        doc.addField(FIELD_DOC_TYPE, DOC_TYPE_ACL_TX);
        return doc;
    }

    // =========================================================================
    // ACL documents
    // =========================================================================

    /**
     * Creates Solr documents for a list of ACL readers.
     * Mirrors {@code SolrInformationServer.indexAcl(List, boolean)}.
     *
     * <p>Document ID format: {@code <tenant>!<encoded-aclId>!ACL}</p>
     *
     * @param aclReadersList the list of ACL readers to convert
     * @return a list of SolrInputDocuments, one per AclReaders entry
     */
    public List<SolrInputDocument> toAclDocs(List<AclReaders> aclReadersList)
    {
        List<SolrInputDocument> docs = new ArrayList<>();
        if (aclReadersList == null)
        {
            return docs;
        }

        for (AclReaders aclReaders : aclReadersList)
        {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField(FIELD_SOLR4_ID, getAclDocumentId(aclReaders.getTenantDomain(), aclReaders.getId()));
            doc.addField(FIELD_VERSION, "0");
            doc.addField(FIELD_ACLID, aclReaders.getId());
            doc.addField(FIELD_INACLTXID, aclReaders.getAclChangeSetId());

            String tenant = aclReaders.getTenantDomain();
            if (aclReaders.getReaders() != null)
            {
                for (String reader : aclReaders.getReaders())
                {
                    reader = addTenantToAuthority(reader, tenant);
                    doc.addField(FIELD_READER, reader);
                }
            }

            if (aclReaders.getDenied() != null)
            {
                for (String denied : aclReaders.getDenied())
                {
                    denied = addTenantToAuthority(denied, tenant);
                    doc.addField(FIELD_DENIED, denied);
                }
            }

            doc.addField(FIELD_DOC_TYPE, DOC_TYPE_ACL);
            docs.add(doc);
        }
        return docs;
    }

    // =========================================================================
    // Node document (structural/tracking fields only)
    // =========================================================================

    /**
     * Creates a basic Solr document for a Node with its metadata.
     * Mirrors the structural fields from {@code SolrInformationServer.basicDocument(NodeMetaData, String, Supplier)}.
     *
     * <p>This first iteration only includes tracking/structural fields (id, DOC_TYPE,
     * DBID, INTXID, LID, ACLID). Full metadata mapping (paths, properties, aspects,
     * ancestors, etc.) will be added incrementally.</p>
     *
     * <p>Document ID format: {@code <tenant>!<encoded-dbid>}</p>
     *
     * @param node     the node (provides txnId for INTXID when metadata is unavailable)
     * @param metadata the node metadata from the repository
     * @return a SolrInputDocument with structural fields populated
     */
    public SolrInputDocument toNodeDoc(Node node, NodeMetaData metadata)
    {
        SolrInputDocument doc = new SolrInputDocument();

        String tenantDomain = metadata.getTenantDomain();
        doc.setField(FIELD_SOLR4_ID, getNodeDocumentId(tenantDomain, metadata.getId()));
        doc.setField(FIELD_VERSION, 0);
        doc.addField(FIELD_DBID, metadata.getId());
        doc.setField(FIELD_INTXID, metadata.getTxnId());
        doc.setField(FIELD_DOC_TYPE, DOC_TYPE_NODE);
        doc.setField(FIELD_ACLID, metadata.getAclId());

        if (metadata.getNodeRef() != null)
        {
            doc.setField(FIELD_LID, metadata.getNodeRef().toString());
        }

        // --- Metadata fields ---

        if (metadata.getType() != null)
        {
            doc.setField(FIELD_TYPE, metadata.getType().toString());
        }

        doc.setField(FIELD_ISNODE, "T");
        doc.setField(FIELD_TENANT, getTenantId(tenantDomain));

        if (metadata.getOwner() != null)
        {
            doc.setField(FIELD_OWNER, metadata.getOwner());
        }

        // Aspects
        Set<QName> aspects = metadata.getAspects();
        if (aspects != null)
        {
            for (QName aspect : aspects)
            {
                if (aspect != null)
                {
                    doc.addField(FIELD_ASPECT, aspect.toString());
                }
            }
        }

        // Paths
        List<Pair<String, QName>> paths = metadata.getPaths();
        if (paths != null)
        {
            for (Pair<String, QName> path : paths)
            {
                if (path != null && path.getFirst() != null)
                {
                    doc.addField(FIELD_PATH, path.getFirst());
                }
            }
        }

        // Ancestors
        Set<NodeRef> ancestors = metadata.getAncestors();
        if (ancestors != null)
        {
            for (NodeRef ancestor : ancestors)
            {
                if (ancestor != null)
                {
                    doc.addField(FIELD_ANCESTOR, ancestor.toString());
                }
            }
        }

        // Parent associations
        List<ChildAssociationRef> parentAssocs = metadata.getParentAssocs();
        if (parentAssocs != null)
        {
            for (ChildAssociationRef assoc : parentAssocs)
            {
                doc.addField(FIELD_PARENT, assoc.getParentRef().toString());
                if (assoc.isPrimary())
                {
                    doc.setField(FIELD_PRIMARYPARENT, assoc.getParentRef().toString());
                    if (assoc.getTypeQName() != null)
                    {
                        doc.setField(FIELD_PRIMARYASSOCTYPEQNAME, assoc.getTypeQName().toString());
                    }
                    if (assoc.getQName() != null)
                    {
                        doc.setField(FIELD_PRIMARYASSOCQNAME, assoc.getQName().toString());
                    }
                }
            }
            doc.setField(FIELD_PARENT_ASSOC_CRC, metadata.getParentAssocsCrc());
        }

        // Properties — also detect content properties for content tracking
        boolean hasContentProperty = false;
        boolean hasUnresolvedProperty = false;
        Map<QName, PropertyValue> properties = metadata.getProperties();
        if (properties != null)
        {
            for (Map.Entry<QName, PropertyValue> entry : properties.entrySet())
            {
                QName propQName = entry.getKey();
                PropertyValue value = entry.getValue();
                if (value == null)
                {
                    doc.addField(FIELD_NULLPROPERTIES, propQName.toString());
                    continue;
                }

                if (value instanceof ContentPropertyValue)
                {
                    hasContentProperty = true;
                }

                PropertyDefinition propDef = dictionaryService != null
                        ? dictionaryService.getPropertyDefinition(propQName) : null;

                if (propDef != null)
                {
                    if (propDef.isIndexed())
                    {
                        for (String solrField : fieldMapper.getSolrFieldNames(propDef))
                        {
                            addPropertyValue(doc, solrField, value, propDef.isMultiValued());
                        }
                    }
                }
                else
                {
                    LOGGER.warn("No property definition for {} — falling back to text@s__lt@ prefix. "
                            + "Dictionary may not be loaded.", propQName);
                    addPropertyValue(doc, propQName.toString(), value);
                    hasUnresolvedProperty = true;
                }
                doc.addField(FIELD_PROPERTIES, propQName.toString());
            }
        }

        if (hasUnresolvedProperty)
        {
            doc.setField("HAS_INDEXING_ERROR", "true");
        }

        // Content versioning: mark nodes with content as needing extraction
        if (hasContentProperty)
        {
            doc.setField(FIELD_LAST_INCOMING_CONTENT_VERSION_ID, CONTENT_OUTDATED_MARKER);
            doc.setField(FIELD_FTSSTATUS, "Dirty");
        }
        else
        {
            doc.setField(FIELD_LAST_INCOMING_CONTENT_VERSION_ID, CONTENT_UPDATED_MARKER);
            doc.setField(FIELD_FTSSTATUS, "Clean");
        }

        return doc;
    }

    /**
     * Adds a property value to the document using Solr dynamic field naming conventions.
     * When called without a dictionary-resolved field name, applies default prefixes:
     * <ul>
     *   <li>{@code text@s__lt@{ns}localName} — single-valued text (StringPropertyValue)</li>
     *   <li>{@code mltext@m__lt@{ns}localName} — multi-valued MLText</li>
     *   <li>{@code content@s__locale@{ns}localName} etc. — content metadata</li>
     * </ul>
     *
     * @param doc       the document to add fields to
     * @param propQName the QName string of the property (used as base for field name prefixing)
     * @param value     the property value (never null)
     */
    private void addPropertyValue(SolrInputDocument doc, String propQName, PropertyValue value)
    {
        addPropertyValuePrefixed(doc, propQName, value, false);
    }

    /**
     * Adds a property value using a pre-resolved Solr field name from the dictionary.
     * No prefix is added — the solrField is used directly for string/text values.
     * For multi-property values, sub-values are added to the same field.
     */
    void addPropertyValue(SolrInputDocument doc, String solrField, PropertyValue value, boolean multiValued)
    {
        if (value instanceof StringPropertyValue)
        {
            doc.addField(solrField, ((StringPropertyValue) value).getValue());
        }
        else if (value instanceof MLTextPropertyValue)
        {
            MLTextPropertyValue mlText = (MLTextPropertyValue) value;
            for (Map.Entry<Locale, String> localeEntry : mlText.getValues().entrySet())
            {
                String localeValue = "\u0000" + localeEntry.getKey() + "\u0000" + localeEntry.getValue();
                doc.addField(solrField, localeValue);
            }
        }
        else if (value instanceof MultiPropertyValue)
        {
            for (PropertyValue subValue : ((MultiPropertyValue) value).getValues())
            {
                if (subValue != null)
                {
                    addPropertyValue(doc, solrField, subValue, true);
                }
            }
        }
        else if (value instanceof ContentPropertyValue)
        {
            addContentPropertyValue(doc, solrField, (ContentPropertyValue) value);
        }
    }

    /**
     * Adds a property value with auto-generated Solr field name prefixes (fallback mode).
     */
    private void addPropertyValuePrefixed(SolrInputDocument doc, String propQName,
                                          PropertyValue value, boolean multiValued)
    {
        if (value instanceof StringPropertyValue)
        {
            String prefix = multiValued ? "text@m__lt@" : "text@s__lt@";
            doc.addField(prefix + propQName, ((StringPropertyValue) value).getValue());
        }
        else if (value instanceof MLTextPropertyValue)
        {
            MLTextPropertyValue mlText = (MLTextPropertyValue) value;
            String solrField = "mltext@m__lt@" + propQName;
            for (Map.Entry<Locale, String> localeEntry : mlText.getValues().entrySet())
            {
                String localeValue = "\u0000" + localeEntry.getKey() + "\u0000" + localeEntry.getValue();
                doc.addField(solrField, localeValue);
            }
        }
        else if (value instanceof MultiPropertyValue)
        {
            for (PropertyValue subValue : ((MultiPropertyValue) value).getValues())
            {
                if (subValue != null)
                {
                    addPropertyValuePrefixed(doc, propQName, subValue, true);
                }
            }
        }
        else if (value instanceof ContentPropertyValue)
        {
            ContentPropertyValue content = (ContentPropertyValue) value;
            if (content.getLocale() != null)
            {
                doc.addField("content@s__locale@" + propQName, content.getLocale().toString());
            }
            if (content.getMimetype() != null)
            {
                doc.addField("content@s__mimetype@" + propQName, content.getMimetype());
            }
            if (content.getEncoding() != null)
            {
                doc.addField("content@s__encoding@" + propQName, content.getEncoding());
            }
            doc.addField("content@s__size@" + propQName, content.getLength());
        }
    }

    /**
     * Adds content property metadata fields (locale, mimetype, encoding, size)
     * using a pre-resolved base Solr field name. Replaces the text part of the
     * field with content-specific suffixes.
     */
    private void addContentPropertyValue(SolrInputDocument doc, String solrField, ContentPropertyValue content)
    {
        // Extract the QName part from the field name (everything after the last @)
        int lastAt = solrField.lastIndexOf('@');
        String qnamePart = lastAt >= 0 ? solrField.substring(lastAt + 1) : solrField;
        if (content.getLocale() != null)
        {
            doc.addField("content@s__locale@" + qnamePart, content.getLocale().toString());
        }
        if (content.getMimetype() != null)
        {
            doc.addField("content@s__mimetype@" + qnamePart, content.getMimetype());
        }
        if (content.getEncoding() != null)
        {
            doc.addField("content@s__encoding@" + qnamePart, content.getEncoding());
        }
        doc.addField("content@s__size@" + qnamePart, content.getLength());
    }

    // =========================================================================
    // Tracker state document
    // =========================================================================

    /**
     * Creates a Solr document that records the tracker's knowledge of the repository state.
     * The ConsistencyComponent reads this document to calculate {@code txRemaining}.
     *
     * <p>Document ID: {@code TRACKER!STATE}, DOC_TYPE: {@code State}.
     * Reuses the existing {@code S_TXID} and {@code S_TXCOMMITTIME} schema fields.</p>
     */
    public SolrInputDocument toTrackerStateDoc(long lastTxIdOnServer, long lastTxCommitTimeOnServer)
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField(FIELD_SOLR4_ID, "TRACKER!STATE");
        doc.setField(FIELD_DOC_TYPE, "State");
        doc.setField(FIELD_S_TXID, lastTxIdOnServer);
        doc.setField(FIELD_S_TXCOMMITTIME, lastTxCommitTimeOnServer);
        return doc;
    }

    // =========================================================================
    // ID generation — mirrors AlfrescoSolrDataModel
    // =========================================================================

    /**
     * Format: {@code TRACKER!TX!<encoded-txId>}
     */
    static String getTransactionDocumentId(long txId)
    {
        return "TRACKER!TX!" + NumericEncoder.encode(txId);
    }

    /**
     * Format: {@code TRACKER!CHANGE_SET!<encoded-changeSetId>}
     */
    static String getAclChangeSetDocumentId(long aclChangeSetId)
    {
        return "TRACKER!CHANGE_SET!" + NumericEncoder.encode(aclChangeSetId);
    }

    /**
     * Format: {@code <tenant>!<encoded-aclId>!ACL}
     */
    static String getAclDocumentId(String tenant, long aclId)
    {
        return getTenantId(tenant) + "!" + NumericEncoder.encode(aclId) + "!ACL";
    }

    /**
     * Format: {@code <tenant>!<encoded-dbid>}
     */
    static String getNodeDocumentId(String tenant, long dbid)
    {
        return getTenantId(tenant) + "!" + NumericEncoder.encode(dbid);
    }

    private static String getTenantId(String tenant)
    {
        if (tenant == null || tenant.isEmpty())
        {
            return DEFAULT_TENANT;
        }
        return tenant.replaceAll("!", "_-._");
    }

    /**
     * Appends the tenant domain to group/everyone/guest authorities.
     * Mirrors {@code SolrInformationServer.addTenantToAuthority(String, String)}.
     */
    private static String addTenantToAuthority(String authority, String tenant)
    {
        if (tenant != null && !tenant.isEmpty())
        {
            if (authority.startsWith("GROUP_") || authority.equals("ROLE_EVERYONE") || authority.equals("ROLE_GUEST"))
            {
                return authority + "@" + tenant;
            }
        }
        return authority;
    }
}
