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

import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.Transaction;
import org.alfresco.util.NumericEncoder;
import org.apache.solr.common.SolrInputDocument;

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

    /**
     * Creates a mapper.
     *
     * @param cascadeTrackingEnabled whether to set the cascade flag on transaction documents
     */
    public SolrDocumentMapper(boolean cascadeTrackingEnabled)
    {
        this.cascadeTrackingEnabled = cascadeTrackingEnabled;
    }

    /**
     * Creates a mapper with cascade tracking disabled.
     */
    public SolrDocumentMapper()
    {
        this(false);
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
