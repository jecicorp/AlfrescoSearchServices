/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
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

package org.alfresco.solr;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ACLID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ACLTXCOMMITTIME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ACLTXID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ANAME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ANCESTOR;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_APATH;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ASPECT;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ASSOCTYPEQNAME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_DBID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_DENIED;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_DOC_TYPE;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_INACLTXID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_INTXID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_ISNODE;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_LID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_OWNER;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PARENT;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PARENT_ASSOC_CRC;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PATH;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PRIMARYASSOCQNAME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PRIMARYASSOCTYPEQNAME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_PRIMARYPARENT;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_QNAME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_READER;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_SOLR4_ID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_TENANT;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_TXCOMMITTIME;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_TXID;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_TYPE;
import static org.alfresco.repo.search.adaptor.QueryConstants.FIELD_VERSION;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.AbstractAlfrescoSolrIT.SolrServletRequest;
import org.alfresco.solr.AlfrescoSolrDataModel.FieldUse;
import org.alfresco.solr.AlfrescoSolrDataModel.SpecializedFieldType;
import org.alfresco.solr.client.Acl;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.ContentPropertyValue;
import org.alfresco.solr.client.MLTextPropertyValue;
import org.alfresco.solr.client.MultiPropertyValue;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIQueueClient;
import org.alfresco.solr.client.StringPropertyValue;
import org.alfresco.solr.client.Transaction;
import org.alfresco.repo.search.adaptor.QueryConstants;
import org.alfresco.util.ISO9075;
import org.alfresco.util.Pair;
import org.apache.solr.SolrTestCaseJ4.XmlDoc;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.XML;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.CommitUpdateCommand;
import org.springframework.extensions.surf.util.I18NUtil;

/**
 * Alfresco Solr Test Utility class which provide helper methods.
 *
 * @author Michael Suzuki
 * @author Andrea Gazzarini
 */
public class AlfrescoSolrUtils
{
    public static final String TEST_NAMESPACE = "http://www.alfresco.org/test/solrtest";
    public static long MAX_WAIT_TIME = 80000;
    public static Random RANDOMIZER = new Random();

    /**
     * Get transaction.
     * When getting an unique transaction for a test, don't use this constructors.
     * As this produces a number that can be out of the range [1-2000], that is
     * the one checked by the SOLR Core to find the initial transaction is right.
     * @param deletes
     * @param updates
     * @return {@link Transaction}
     */
    public static Transaction getTransaction(int deletes, int updates)
    {
        return getTransaction(deletes, updates, generateId());
    }

    public static Transaction getTransaction(int deletes, int updates, long id)
    {
        return getTransaction(deletes, updates, id, System.currentTimeMillis());
    }

    public static Transaction getTransaction(int deletes, int updates, long id, long timestamp)
    {
        long txnCommitTime = timestamp;
        Transaction transaction = new Transaction();
        transaction.setCommitTimeMs(txnCommitTime);
        transaction.setId(id);
        transaction.setDeletes(deletes);
        transaction.setUpdates(updates);
        return transaction;
    }

    /**
     * Returns a pseudo-random number of shards always greater than 1.
     *
     * @return a pseudo-random number of shards always greater than 1.
     */
    public static int randomShardCountGreaterThanOne()
    {
        return randomPositiveInteger() +  2;
    }

    /**
     * Returns a pseudo-random number of shards always greater than 1.
     *
     * @return a pseudo-random number of shards always greater than 1.
     */
    public static int randomPositiveInteger()
    {
        return RANDOMIZER.nextInt(100);
    }

    /**
     * Get a node.
     * @param txn
     * @param acl
     * @param status
     * @return {@link Node}
     */
    public static Node getNode(Transaction txn, Acl acl, Node.SolrApiNodeStatus status)
    {
        Node node = new Node();
        node.setTxnId(txn.getId());
        node.setId(generateId());
        node.setAclId(acl.getId());
        node.setStatus(status);
        return node;
    }


    /**
     * Get a node.
     * @param nodeId
     * @param txn
     * @param acl
     * @param status
     * @return {@link Node}
     */
    public static Node getNode(long nodeId, Transaction txn, Acl acl, Node.SolrApiNodeStatus status)
    {
        Node node = new Node();
        node.setTxnId(txn.getId());
        node.setId(nodeId);
        node.setAclId(acl.getId());
        node.setStatus(status);
        return node;
    }

    /**
     * Get a nodes meta data.
     * @param node
     * @param txn
     * @param acl
     * @param owner
     * @param ancestors
     * @param createError
     * @return {@link NodeMetaData}
     */
    public static NodeMetaData getNodeMetaData(Node node, Transaction txn, Acl acl, String owner, Set<NodeRef> ancestors, boolean createError)
    {
        NodeMetaData nodeMetaData = new NodeMetaData();
        nodeMetaData.setId(node.getId());
        nodeMetaData.setAclId(acl.getId());
        nodeMetaData.setTxnId(txn.getId());
        nodeMetaData.setOwner(owner);
        nodeMetaData.setAspects(new HashSet<>());
        nodeMetaData.setAncestors(ancestors);
        Map<QName, PropertyValue> props = new HashMap<>();
        props.put(ContentModel.PROP_IS_INDEXED, new StringPropertyValue("true"));
        props.put(ContentModel.PROP_CONTENT, new ContentPropertyValue(Locale.US, 0L, "UTF-8", "text/plain", null));
        nodeMetaData.setProperties(props);
        //If create createError is true then we leave out the nodeRef which will cause an error
        if(!createError) {
            NodeRef nodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), createGUID());
            nodeMetaData.setNodeRef(nodeRef);
        }
        nodeMetaData.setType(QName.createQName(TEST_NAMESPACE, "testSuperType"));
        nodeMetaData.setAncestors(ancestors);
        nodeMetaData.setPaths(new ArrayList<>());
        nodeMetaData.setNamePaths(new ArrayList<>());
        return nodeMetaData;
    }
    /**
     * Create GUID
     * @return String guid
     */
    public static String createGUID()
    {
        long id = generateId();
        return "00000000-0000-" + ((id / 1000000000000L) % 10000L) + "-" + ((id / 100000000L) % 10000L) + "-"
                + (id % 100000000L);
    }
    /**
     * Creates a set of NodeRef from input
     * @param refs
     * @return
     */
    public static Set<NodeRef> ancestors(NodeRef... refs) 
    {
        Set<NodeRef> set = new HashSet<NodeRef>();
        for(NodeRef ref : refs) {
            set.add(ref);
        }
        return set;
    }
    /**
     * 
     * @param transaction
     * @param nodes
     * @param nodeMetaDatas
     */
    public void indexTransaction(Transaction transaction, List<Node> nodes, List<NodeMetaData> nodeMetaDatas)
    {
        //First map the nodes to a transaction.
        SOLRAPIQueueClient.NODE_MAP.put(transaction.getId(), nodes);

        //Next map a node to the NodeMetaData
        for(NodeMetaData nodeMetaData : nodeMetaDatas)
        {
            SOLRAPIQueueClient.NODE_META_DATA_MAP.put(nodeMetaData.getId(), nodeMetaData);
        }

        //Next add the transaction to the queue
        SOLRAPIQueueClient.TRANSACTION_QUEUE.add(transaction);
    }
    /**
     * 
     * @param aclChangeSet
     * @return
     */
    public static Acl getAcl(AclChangeSet aclChangeSet)
    {
        Acl acl = new Acl(aclChangeSet.getId(), generateId());
        return acl;
    }

    /**
     *
     * @param aclChangeSet
     * @return
     */
    public static Acl getAcl(AclChangeSet aclChangeSet, long aclId)
    {
        Acl acl = new Acl(aclChangeSet.getId(), aclId);
        return acl;
    }

    /**
     * Get an AclChangeSet
     * @param aclCount
     * @return {@link AclChangeSet}
     */
    public static AclChangeSet getAclChangeSet(int aclCount)
    {
        return new AclChangeSet(generateId(), System.currentTimeMillis(), aclCount);
    }

    public static AclChangeSet getAclChangeSet(int aclCount, long id)
    {
        return new AclChangeSet(id, System.currentTimeMillis(), aclCount);
    }

    public static AclChangeSet getAclChangeSet(int aclCount, long id, long timestamp)
    {
        return new AclChangeSet(id, timestamp, aclCount);
    }

    private static AtomicLong id = new AtomicLong(System.currentTimeMillis());
    /**
     * Creates a unique id.
     * @return Long unique id
     */
    private static synchronized Long generateId()
    {
        return id.incrementAndGet();
    }
    /**
     * Generates an &lt;add&gt;&lt;doc&gt;... XML String with options
     * on the add.
     *
     * @param doc the Document to add
     * @param args 0th and Even numbered args are param names, Odds are param values.
     * @see #add
     */
    public static String add(XmlDoc doc, String... args)
    {
        try {
        StringWriter r = new StringWriter();
        // this is annoying
        if (null == args || 0 == args.length)
        {
            r.write("<add>");
            r.write(doc.xml);
            r.write("</add>");
        } 
        else
        {
            XML.writeUnescapedXML(r, "add", doc.xml, (Object[])args);
        }
            return r.getBuffer().toString();
        } 
        catch (IOException e) 
        {
            throw new RuntimeException("this should never happen with a StringWriter", e);
        }
    }
    /**
     * Get an AclReader.
     * @param aclChangeSet
     * @param acl
     * @param readers
     * @param denied
     * @param tenant
     * @return
     */
    public static AclReaders getAclReaders(AclChangeSet aclChangeSet, Acl acl, List<String> readers, List<String> denied, String tenant)
    {
        if(tenant == null)
        {
            tenant = TenantService.DEFAULT_DOMAIN;
        }
        return new AclReaders(acl.getId(), readers, denied, aclChangeSet.getId(), tenant);
    }
    /**
     * 
     * @param aclChangeSet
     * @param aclList
     * @param aclReadersList
     */
    public static void indexAclChangeSet(AclChangeSet aclChangeSet, List<Acl> aclList, List<AclReaders> aclReadersList)
    {
        //First map the nodes to a transaction.
        SOLRAPIQueueClient.ACL_MAP.put(aclChangeSet.getId(), aclList);

        //Next map a node to the NodeMetaData
        for(AclReaders aclReaders : aclReadersList)
        {
            SOLRAPIQueueClient.ACL_READERS_MAP.put(aclReaders.getId(), aclReaders);
        }

        //Next add the transaction to the queue

        SOLRAPIQueueClient.ACL_CHANGE_SET_QUEUE.add(aclChangeSet);
    }

    /**
     * Builds the Solr documents of an ACL change set: the ACL-TX stamp, plus one ACL document
     * per ACL carrying its real readers and denied authorities.
     * <p>
     * {@link #indexAclChangeSet} only fills the {@link SOLRAPIQueueClient} queues, which a
     * tracker used to drain. The trackers now live in their own module, so nothing reads those
     * queues from this one any more and tests have to index the documents themselves.
     */
    public static List<SolrInputDocument> aclDocuments(AclChangeSet aclChangeSet, List<Acl> aclList,
            List<AclReaders> aclReadersList)
    {
        Map<Long, AclReaders> readersByAclId = new HashMap<>();
        if (aclReadersList != null)
        {
            for (AclReaders aclReaders : aclReadersList)
            {
                readersByAclId.put(aclReaders.getId(), aclReaders);
            }
        }

        List<SolrInputDocument> documents = new ArrayList<>();

        SolrInputDocument aclTx = new SolrInputDocument();
        aclTx.addField(FIELD_SOLR4_ID, AlfrescoSolrDataModel.getAclChangeSetDocumentId(aclChangeSet.getId()));
        aclTx.addField(FIELD_VERSION, "0");
        aclTx.addField(FIELD_ACLTXID, aclChangeSet.getId());
        aclTx.addField(FIELD_INACLTXID, aclChangeSet.getId());
        aclTx.addField(FIELD_ACLTXCOMMITTIME, aclChangeSet.getCommitTimeMs());
        aclTx.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL_TX);
        documents.add(aclTx);

        for (Acl acl : aclList)
        {
            SolrInputDocument aclDocument = new SolrInputDocument();
            aclDocument.addField(FIELD_SOLR4_ID,
                    AlfrescoSolrDataModel.getAclDocumentId(AlfrescoSolrDataModel.DEFAULT_TENANT, acl.getId()));
            aclDocument.addField(FIELD_VERSION, "0");
            aclDocument.addField(FIELD_ACLID, acl.getId());
            aclDocument.addField(FIELD_INACLTXID, acl.getAclChangeSetId());
            aclDocument.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL);

            AclReaders aclReaders = readersByAclId.get(acl.getId());
            if (aclReaders != null)
            {
                aclReaders.getReaders().forEach(reader -> aclDocument.addField(FIELD_READER, reader));
                aclReaders.getDenied().forEach(denied -> aclDocument.addField(FIELD_DENIED, denied));
            }
            documents.add(aclDocument);
        }
        return documents;
    }

    /**
     * Builds the Solr documents of a transaction: the TX stamp, plus one node document per node.
     * {@code content} is optional and positional -- one entry per node, in the same order.
     *
     * @see #aclDocuments(AclChangeSet, List, List) for why tests index directly
     */
    public static List<SolrInputDocument> nodeDocuments(Transaction transaction, List<Node> nodes,
            List<NodeMetaData> nodeMetaDatas, List<String> content)
    {
        return nodeDocuments(transaction, nodes, nodeMetaDatas, content, false);
    }

    /**
     * @see #createDocument(AlfrescoSolrDataModel, Long, Long, NodeRef, QName, QName[], Map, Map, Long, String[], String, ChildAssociationRef[], NodeRef[], boolean)
     *      for what {@code storeTextProperties} does and why it is not the default
     */
    public static List<SolrInputDocument> nodeDocuments(Transaction transaction, List<Node> nodes,
            List<NodeMetaData> nodeMetaDatas, List<String> content, boolean storeTextProperties)
    {
        AlfrescoSolrDataModel dataModel = AlfrescoSolrDataModel.getInstance();
        List<SolrInputDocument> documents = new ArrayList<>();

        SolrInputDocument tx = new SolrInputDocument();
        tx.addField(FIELD_SOLR4_ID, AlfrescoSolrDataModel.getTransactionDocumentId(transaction.getId()));
        tx.addField(FIELD_VERSION, "0");
        tx.addField(FIELD_TXID, transaction.getId());
        tx.addField(FIELD_INTXID, transaction.getId());
        tx.addField(FIELD_TXCOMMITTIME, transaction.getCommitTimeMs());
        tx.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_TX);
        documents.add(tx);

        for (int i = 0; i < nodes.size(); i++)
        {
            Node node = nodes.get(i);
            NodeMetaData nodeMetaData = nodeMetaDatas.get(i);

            Map<QName, String> contentByProperty = null;
            if (content != null && i < content.size())
            {
                contentByProperty = Map.of(ContentModel.PROP_CONTENT, content.get(i));
            }

            documents.add(createDocument(dataModel,
                    transaction.getId(),
                    node.getId(),
                    nodeMetaData.getNodeRef(),
                    nodeMetaData.getType(),
                    toArray(nodeMetaData.getAspects(), QName[]::new),
                    nodeMetaData.getProperties(),
                    contentByProperty,
                    node.getAclId(),
                    pathsOf(nodeMetaData),
                    nodeMetaData.getOwner(),
                    toArray(nodeMetaData.getParentAssocs(), ChildAssociationRef[]::new),
                    toArray(nodeMetaData.getAncestors(), NodeRef[]::new),
                    storeTextProperties));
        }
        return documents;
    }

    /**
     * Loads the bootstrap data models of a solr home into the {@link AlfrescoSolrDataModel}
     * dictionary, in dependency order (dictionary -> system -> content -> cmis), then the test
     * models, then refreshes the CMIS dictionary.
     * <p>
     * Since the trackers were externalized the data model loads nothing on startup -- the
     * (separate) ModelTracker pushes models through {@code putModel()}. Tests have no tracker,
     * so both harnesses call this. Without it, {@code getPropertyDefinition} returns null and
     * every property-to-field lookup fails.
     */
    public static void loadBootstrapModels(String solrHome) throws IOException
    {
        AlfrescoSolrDataModel dataModel = AlfrescoSolrDataModel.getInstance();

        // Base dictionary model ships in alfresco-data-model.
        try (InputStream is = AlfrescoSolrUtils.class.getClassLoader()
                .getResourceAsStream("alfresco/model/dictionaryModel.xml"))
        {
            if (is != null)
            {
                dataModel.putModel(M2Model.createModel(is));
            }
        }

        File modelsDir = Paths.get(solrHome, "alfrescoModels").toFile();
        File[] modelFiles = modelsDir.listFiles((dir, name) -> name.endsWith(".xml"));
        if (modelFiles != null)
        {
            Set<String> loaded = new HashSet<>();

            // Explicit dependency order: system, then content, then cmis.
            for (String token : new String[]{ "systemmodel", "contentmodel", "cmismodel" })
            {
                for (File modelFile : modelFiles)
                {
                    if (modelFile.getName().contains(token))
                    {
                        putModel(dataModel, modelFile);
                        loaded.add(modelFile.getName());
                    }
                }
            }

            // Then the test models (cmistest, acme, ...), which import d/sys/cm only. The dictionary
            // came from the classpath above; reloading it here would recompile its importers.
            for (File modelFile : modelFiles)
            {
                if (!loaded.contains(modelFile.getName()) && !modelFile.getName().contains("dictionary"))
                {
                    putModel(dataModel, modelFile);
                }
            }
        }

        dataModel.afterInitModels();
    }

    private static void putModel(AlfrescoSolrDataModel dataModel, File modelFile) throws IOException
    {
        try (InputStream is = new FileInputStream(modelFile))
        {
            dataModel.putModel(M2Model.createModel(is));
        }
    }

    /**
     * Writes one property value onto the document. {@link MultiPropertyValue} recurses, which is
     * what makes the multi-valued test properties (@{code any-many-ista}, @{code mltext-many-ista})
     * land in the index at all -- an unhandled value type is simply dropped here.
     *
     * @param sortWritten guards the sort field, which is single-valued: only the first value of a
     *        property may write it.
     */
    private static void addPropertyValue(SolrInputDocument doc, AlfrescoSolrDataModel dataModel,
            QName propQName, PropertyValue value, boolean storeTextProperties, Set<QName> sortWritten)
    {
        if (value instanceof MultiPropertyValue)
        {
            for (PropertyValue nested : ((MultiPropertyValue) value).getValues())
            {
                addPropertyValue(doc, dataModel, propQName, nested, storeTextProperties, sortWritten);
            }
        }
        else if (value instanceof StringPropertyValue)
        {
            String text = ((StringPropertyValue) value).getValue();
            if (text == null)
            {
                return;
            }
            if (storeTextProperties && isTextProperty(propQName))
            {
                // The marker carries the *language*, not the full locale: LanguagePrefixedTokenStream
                // reads a 5-char buffer and only accepts NUL + 2-or-3-char code + NUL, so "en_US"
                // is not recognised and the analysis falls back to text___ -- which has no stemmer,
                // so highlighting a stemmed match (discuss -> discussion) finds nothing.
                doc.addField(dataModel.getStoredTextField(propQName),
                        "\u0000" + I18NUtil.getLocale().getLanguage() + "\u0000" + text);
            }
            else
            {
                for (AlfrescoSolrDataModel.FieldInstance field : dataModel.getIndexedFieldNamesForProperty(propQName).getFields())
                {
                    doc.addField(field.getField(), text);
                }
            }
        }
        else if (value instanceof ContentPropertyValue)
        {
            // Otherwise the content sub-properties are silently dropped and
            // @cm:content.locale / .mimetype / .size / .encoding match nothing. The fields are
            // resolved the way Solr4QueryParser resolves them when querying.
            ContentPropertyValue contentProperty = (ContentPropertyValue) value;
            addSpecializedField(doc, propQName, SpecializedFieldType.CONTENT_LOCALE, contentProperty.getLocale());
            addSpecializedField(doc, propQName, SpecializedFieldType.CONTENT_MIMETYPE, contentProperty.getMimetype());
            addSpecializedField(doc, propQName, SpecializedFieldType.CONTENT_ENCODING, contentProperty.getEncoding());
            addSpecializedField(doc, propQName, SpecializedFieldType.CONTENT_SIZE, contentProperty.getLength());
        }
        else if (value instanceof MLTextPropertyValue)
        {
            // The stored field is what the schema copyFields fan out to the indexed variants, and
            // what [fmap] returns.
            MLTextPropertyValue mlText = (MLTextPropertyValue) value;
            String storedField = dataModel.getStoredMLTextField(propQName);
            for (Locale mlLocale : mlText.getLocales())
            {
                doc.addField(storedField,
                        "\u0000" + mlLocale.toString() + "\u0000" + mlText.getValue(mlLocale));
            }
            // mltext@m__sort@* has no copyField source, unlike text@s__sort@*, so the indexer
            // writes it and the fixture has to as well. It is single-valued and holds *every*
            // locale in one string: AlfrescoCollatableMLTextFieldType's comparator splits on
            // \u0000 and steps three parts at a time, picking the segment closest to the request
            // locale -- hence the extra \u0000 joining consecutive pairs.
            if (sortWritten.add(propQName))
            {
                String sortValue = mlText.getLocales().stream()
                        .map(locale -> "\u0000" + locale.toString() + "\u0000" + mlText.getValue(locale))
                        .collect(Collectors.joining("\u0000"));
                dataModel.getQueryableFields(propQName, null, FieldUse.SORT).getFields()
                        .forEach(field -> doc.addField(field.getField(), sortValue));
            }
        }
    }

    /** No value at all, a {@link StringPropertyValue} holding null, or a multi value of those. */
    private static boolean isNullValue(PropertyValue value)
    {
        if (value == null)
        {
            return true;
        }
        if (value instanceof StringPropertyValue)
        {
            return ((StringPropertyValue) value).getValue() == null;
        }
        if (value instanceof MultiPropertyValue)
        {
            List<PropertyValue> values = ((MultiPropertyValue) value).getValues();
            return values.isEmpty() || values.stream().allMatch(AlfrescoSolrUtils::isNullValue);
        }
        return false;
    }

    private static void addSpecializedField(SolrInputDocument doc, QName propertyQName,
            SpecializedFieldType type, Object value)
    {
        if (value == null)
        {
            return;
        }
        AlfrescoSolrDataModel.getInstance().getQueryableFields(propertyQName, type, FieldUse.ID).getFields()
                .forEach(field -> doc.addField(field.getField(), value.toString()));
    }

    private static boolean isTextProperty(QName propertyQName)
    {
        PropertyDefinition definition = AlfrescoSolrDataModel.getInstance().getPropertyDefinition(propertyQName);
        return definition != null && DataTypeDefinition.TEXT.equals(definition.getDataType().getName());
    }

    private static String[] pathsOf(NodeMetaData nodeMetaData)
    {
        List<Pair<String, QName>> paths = nodeMetaData.getPaths();
        if (paths == null || paths.isEmpty())
        {
            return null;
        }
        return paths.stream().map(Pair::getFirst).toArray(String[]::new);
    }

    /** Empty collapses to null: createDocument reads null as "leave the field out". */
    private static <T> T[] toArray(Collection<T> values, IntFunction<T[]> factory)
    {
        if (values == null || values.isEmpty())
        {
            return null;
        }
        return values.toArray(factory.apply(values.size()));
    }
    /**
     * Generate a collection from input.
     * @param strings
     * @return {@link List} made from the input
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static List list(Object... strings)
    {
        List list = new ArrayList();
        for(Object s : strings)
        {
            list.add(s);
        }
        return list;
    }
    /**
     * 
     * @param params
     * @return
     */
    public static ModifiableSolrParams params(String... params)
    {
        ModifiableSolrParams msp = new ModifiableSolrParams();
        for (int i=0; i<params.length; i+=2) {
          msp.add(params[i], params[i+1]);
        }
        return msp;
      }
    /**
     * 
     * @param params
     * @return
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static Map map(Object... params)
    {
        LinkedHashMap ret = new LinkedHashMap();
        for (int i=0; i<params.length; i+=2)
        {
            ret.put(params[i], params[i+1]);
        }
        return ret;
    }
    /**
     * 
     * @param core
     * @param dataModel
     * @param txid
     * @param dbid
     * @param aclid
     * @param type
     * @param aspects
     * @param properties
     * @param content
     * @param owner
     * @param parentAssocs
     * @param ancestors
     * @param paths
     * @param nodeRef
     * @param commit
     * @return
     * @throws IOException
     */
    public static NodeRef addNode(SolrCore core, 
                                  AlfrescoSolrDataModel dataModel,
                                  int txid,
                                  int dbid,
                                  int aclid,
                                  QName type,
                                  QName[] aspects,
                                  Map<QName, PropertyValue> properties,
                                  Map<QName, String> content, 
                                  String owner,
                                  ChildAssociationRef[] parentAssocs, 
                                  NodeRef[] ancestors,
                                  String[] paths,
                                  NodeRef nodeRef,
                                  boolean commit)
    {
        SolrServletRequest solrQueryRequest = null;
        try
        {
            solrQueryRequest = new SolrServletRequest(core, null);
            AddUpdateCommand addDocCmd = new AddUpdateCommand(solrQueryRequest);
            addDocCmd.overwrite = true;
            addDocCmd.solrDoc = createDocument(dataModel, Long.valueOf(txid), Long.valueOf(dbid), nodeRef, type, aspects,
                  properties, content, Long.valueOf(aclid), paths, owner, parentAssocs, ancestors);
            core.getUpdateHandler().addDoc(addDocCmd);
            if (commit)
            {
                core.getUpdateHandler().commit(new CommitUpdateCommand(solrQueryRequest, false));
            }
        }
        catch (IOException exception)
        {
            throw new RuntimeException(exception);
        }
        finally
        {
            solrQueryRequest.close();
        }
            return nodeRef;
        }
    /**
     * 
     * @param dataModel
     * @param txid
     * @param dbid
     * @param nodeRef
     * @param type
     * @param aspects
     * @param properties
     * @param content
     * @param aclId
     * @param paths
     * @param owner
     * @param parentAssocs
     * @param ancestors
     * @return
     * @throws IOException
     */
    public static SolrInputDocument createDocument(AlfrescoSolrDataModel dataModel,
                                                   Long txid,
                                                   Long dbid,
                                                   NodeRef nodeRef,
                                                   QName type,
                                                   QName[] aspects,
                                                   Map<QName, PropertyValue> properties,
                                                   Map<QName, String> content,
                                                   Long aclId, 
                                                   String[] paths,
                                                   String owner, 
                                                   ChildAssociationRef[] parentAssocs,
                                                   NodeRef[] ancestors)
    {
        return createDocument(dataModel, txid, dbid, nodeRef, type, aspects, properties, content, aclId,
                paths, owner, parentAssocs, ancestors, false);
    }

    /**
     * {@code storeTextProperties} routes {@code d:text} properties through their <em>stored</em>
     * field instead of writing the indexed fields directly, letting the schema copyFields fan the
     * value out. Only the highlighter needs it -- it snippets the stored value, and the indexed
     * fields are not stored. It cannot be the default: the two routes both feed
     * {@code text@s__lt@*}, which is single-valued, so doing both fails the update with
     * "Multiple values encountered for non multiValued copy field", and going stored-only changes
     * which tokens the indexed variants carry, breaking the wildcard and range assertions of the
     * tests built on {@link org.alfresco.solr.dataload.TestDataProvider}.
     */
    public static SolrInputDocument createDocument(AlfrescoSolrDataModel dataModel,
                                                   Long txid,
                                                   Long dbid,
                                                   NodeRef nodeRef,
                                                   QName type,
                                                   QName[] aspects,
                                                   Map<QName, PropertyValue> properties,
                                                   Map<QName, String> content,
                                                   Long aclId,
                                                   String[] paths,
                                                   String owner,
                                                   ChildAssociationRef[] parentAssocs,
                                                   NodeRef[] ancestors,
                                                   boolean storeTextProperties)
    {
        SolrInputDocument doc = new SolrInputDocument();
        String id = AlfrescoSolrDataModel.getNodeDocumentId(AlfrescoSolrDataModel.DEFAULT_TENANT, dbid);
        doc.addField(FIELD_SOLR4_ID, id);
        doc.addField(FIELD_VERSION, 0);
        doc.addField(FIELD_DBID, "" + dbid);
        doc.addField(FIELD_LID, String.valueOf(nodeRef));
        doc.addField(FIELD_INTXID, "" + txid);
        doc.addField(FIELD_ACLID, "" + aclId);
        doc.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_NODE);
        if (paths != null)
        {
            for (String path : paths)
            {
                doc.addField(FIELD_PATH, path);
            }
        }
        if (owner != null)
        {
            doc.addField(FIELD_OWNER, owner);
        }
        doc.addField(FIELD_PARENT_ASSOC_CRC, "0");
        StringBuilder qNameBuffer = new StringBuilder(64);
        StringBuilder assocTypeQNameBuffer = new StringBuilder(64);
        if (parentAssocs != null)
        {
            for (ChildAssociationRef childAssocRef : parentAssocs)
            {
                if (qNameBuffer.length() > 0)
                {
                    qNameBuffer.append(";/");
                    assocTypeQNameBuffer.append(";/");
                }
                qNameBuffer.append(ISO9075.getXPathName(childAssocRef.getQName()));
                assocTypeQNameBuffer.append(ISO9075.getXPathName(childAssocRef.getTypeQName()));
                doc.addField(FIELD_PARENT, ofNullable(childAssocRef.getParentRef()).map(Object::toString).orElse(null));
                
                if (childAssocRef.isPrimary())
                {
                    doc.addField(FIELD_PRIMARYPARENT,  ofNullable(childAssocRef.getParentRef()).map(Object::toString).orElse(null));
                    doc.addField(FIELD_PRIMARYASSOCTYPEQNAME,  ISO9075.getXPathName(childAssocRef.getTypeQName()));
                    doc.addField(FIELD_PRIMARYASSOCQNAME, ISO9075.getXPathName(childAssocRef.getQName()));
                }
            }
            doc.addField(FIELD_ASSOCTYPEQNAME, assocTypeQNameBuffer.toString());
            doc.addField(FIELD_QNAME, qNameBuffer.toString());
        }
        
        if (ancestors != null)
        {
        	
            for (NodeRef ancestor : ancestors)
            {
                doc.addField(FIELD_ANCESTOR, ancestor.toString());
            }
            
            StringBuilder builder = new StringBuilder();
            int i = 0;
    		for(NodeRef ancestor : ancestors)
    		{
    			builder.append('/').append(ancestor.getId());
    			doc.addField(FIELD_APATH, "" + i++ + builder.toString());
    		}
    		if(builder.length() > 0)
    		{
    			doc.addField(FIELD_APATH, "F" + builder.toString());
    		}
    		
    		builder = new StringBuilder();
    		for(int j = 0;  j < ancestors.length; j++)
    		{
    			NodeRef element = ancestors[ancestors.length - 1 - j];
    			builder.insert(0, element.getId());
    			builder.insert(0, '/');
    			doc.addField(FIELD_ANAME, "" + j +  builder.toString());
    		}
    		if(builder.length() > 0)
    		{
    			doc.addField(FIELD_ANAME, "F" +  builder.toString());
    		}

        }
        if (properties != null)
        {
            // Simplified property population for tests (SolrInformationServer has been removed)
            AlfrescoSolrDataModel dataModel2 = AlfrescoSolrDataModel.getInstance();
            Set<QName> sortWritten = new HashSet<>();
            for (Map.Entry<QName, PropertyValue> entry : properties.entrySet())
            {
                // ISUNSET / ISNULL / EXISTS / ISNOTNULL are answered from these two fields, not
                // from the value fields: a property present with a null value is "set but null".
                doc.addField(QueryConstants.FIELD_PROPERTIES, entry.getKey().toString());
                if (isNullValue(entry.getValue()))
                {
                    doc.addField(QueryConstants.FIELD_NULLPROPERTIES, entry.getKey().toString());
                }
                addPropertyValue(doc, dataModel2, entry.getKey(), entry.getValue(),
                        storeTextProperties, sortWritten);
            }
            if (content != null)
            {
                addContentToDoc(doc, content);
            }
        }
        
        doc.addField(FIELD_TYPE, String.valueOf(type));
        if (aspects != null)
        {
            for (QName aspect : aspects)
            {
                doc.addField(FIELD_ASPECT, String.valueOf(aspect));
            }
        }
        doc.addField(FIELD_ISNODE, "T");
        doc.addField(FIELD_TENANT, AlfrescoSolrDataModel.DEFAULT_TENANT);

        return doc;
    }
    private static void addContentToDoc(SolrInputDocument doc, Map<QName, String> content)
    {
        AlfrescoSolrDataModel dataModel = AlfrescoSolrDataModel.getInstance();
        Locale locale = I18NUtil.getLocale();
        content.forEach((propertyQName, textContent) -> {
            String storedField = dataModel.getStoredContentField(propertyQName);
            doc.setField(storedField, "\u0000" + locale.toString() + "\u0000" + textContent);
        });

    }


      private static void addContentPropertyToDoc(SolrInputDocument cachedDoc,
              QName propertyQName,
              String locale,
              Map<QName, String> content)
      {
          StringBuilder builder = new StringBuilder();
          builder.append("\u0000").append(locale).append("\u0000");
          builder.append(content.get(propertyQName));

          for (AlfrescoSolrDataModel.FieldInstance field : AlfrescoSolrDataModel.getInstance().getIndexedFieldNamesForProperty(propertyQName).getFields())
          {
              cachedDoc.removeField(field.getField());
              if(field.isLocalised())
              {
                  cachedDoc.addField(field.getField(), builder.toString());
              }
              else
              {
                  cachedDoc.addField(field.getField(), content.get(propertyQName));
              }
          }
      }
      /**
       * Add an acl.
       * @param core
       * @param dataModel
       * @param acltxid
       * @param aclId
       * @param maxReader
       * @param totalReader
       * @throws IOException
       */
      public static void addAcl(SolrCore core,
                                AlfrescoSolrDataModel dataModel, 
                                int acltxid, 
                                int aclId,
                                int maxReader,
                                int totalReader) throws IOException
      {
          SolrQueryRequest solrQueryRequest = new SolrServletRequest(core, null);
          AddUpdateCommand aclTxCmd = new AddUpdateCommand(solrQueryRequest);
          aclTxCmd.overwrite = true;
          SolrInputDocument aclTxSol = new SolrInputDocument();
          String aclTxId = AlfrescoSolrDataModel.getAclChangeSetDocumentId(Long.valueOf(acltxid));
          aclTxSol.addField(FIELD_SOLR4_ID, aclTxId);
          aclTxSol.addField(FIELD_VERSION, "0");
          aclTxSol.addField(FIELD_ACLTXID, acltxid);
          aclTxSol.addField(FIELD_INACLTXID, acltxid);
          aclTxSol.addField(FIELD_ACLTXCOMMITTIME, (new Date()).getTime());
          aclTxSol.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL_TX);
          aclTxCmd.solrDoc = aclTxSol;
          core.getUpdateHandler().addDoc(aclTxCmd);
          AddUpdateCommand aclCmd = new AddUpdateCommand(solrQueryRequest);
          aclCmd.overwrite = true;
          SolrInputDocument aclSol = new SolrInputDocument();
          String aclDocId = AlfrescoSolrDataModel.getAclDocumentId(AlfrescoSolrDataModel.DEFAULT_TENANT, Long.valueOf(aclId));
          aclSol.addField(FIELD_SOLR4_ID, aclDocId);
          aclSol.addField(FIELD_VERSION, "0");
          aclSol.addField(FIELD_ACLID, aclId);
          aclSol.addField(FIELD_INACLTXID, "" + acltxid);
          aclSol.addField(FIELD_READER, "GROUP_EVERYONE");
          aclSol.addField(FIELD_READER, "pig");
          for (int i = 0; i <= maxReader; i++)
          {
              aclSol.addField(FIELD_READER, "READER-" + (totalReader - i));
          }
          aclSol.addField(FIELD_DENIED, "something");
          aclSol.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL);
          aclCmd.solrDoc = aclSol;
          core.getUpdateHandler().addDoc(aclCmd);
    }
    /**
     * Add a store to root.  
     * @param core
     * @param dataModel
     * @param rootNodeRef
     * @param txid
     * @param dbid
     * @param acltxid
     * @param aclid
     * @throws IOException
     */
    public static void addStoreRoot(SolrCore core,
                                      AlfrescoSolrDataModel dataModel,
                                      NodeRef rootNodeRef,
                                      int txid,
                                      int dbid,
                                      int acltxid,
                                      int aclid) throws IOException
      {
          SolrServletRequest solrQueryRequest = null;
          try
          {
              solrQueryRequest = new SolrServletRequest(core, null);
              AddUpdateCommand addDocCmd = new AddUpdateCommand(solrQueryRequest);
              addDocCmd.overwrite = true;
              addDocCmd.solrDoc = createDocument(dataModel, Long.valueOf(txid), Long.valueOf(dbid), rootNodeRef,
                      ContentModel.TYPE_STOREROOT, new QName[]{ContentModel.ASPECT_ROOT}, null, null, Long.valueOf(aclid),
                      new String[]{"/"}, "system", null, null);
              core.getUpdateHandler().addDoc(addDocCmd);
              addAcl(solrQueryRequest, core, dataModel, acltxid, aclid, 0, 0);
              AddUpdateCommand txCmd = new AddUpdateCommand(solrQueryRequest);
              txCmd.overwrite = true;
              SolrInputDocument input = new SolrInputDocument();
              String id = AlfrescoSolrDataModel.getTransactionDocumentId(Long.valueOf(txid));
              input.addField(FIELD_SOLR4_ID, id);
              input.addField(FIELD_VERSION, "0");
              input.addField(FIELD_TXID, txid);
              input.addField(FIELD_INTXID, txid);
              input.addField(FIELD_TXCOMMITTIME, (new Date()).getTime());
              input.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_TX);
              txCmd.solrDoc = input;
              core.getUpdateHandler().addDoc(txCmd);
              core.getUpdateHandler().commit(new CommitUpdateCommand(solrQueryRequest, false));
          }
              finally
          {
              solrQueryRequest.close();
          }
    }
    public static void addAcl(SolrQueryRequest solrQueryRequest, SolrCore core, AlfrescoSolrDataModel dataModel, int acltxid, int aclId, int maxReader,
            int totalReader) throws IOException
    {
        AddUpdateCommand aclTxCmd = new AddUpdateCommand(solrQueryRequest);
        aclTxCmd.overwrite = true;
        SolrInputDocument aclTxSol = new SolrInputDocument();
        String aclTxId = AlfrescoSolrDataModel.getAclChangeSetDocumentId(Long.valueOf(acltxid));
        aclTxSol.addField(FIELD_SOLR4_ID, aclTxId);
        aclTxSol.addField(FIELD_VERSION, "0");
        aclTxSol.addField(FIELD_ACLTXID, acltxid);
        aclTxSol.addField(FIELD_INACLTXID, acltxid);
        aclTxSol.addField(FIELD_ACLTXCOMMITTIME, (new Date()).getTime());
        aclTxSol.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL_TX);
        aclTxCmd.solrDoc = aclTxSol;
        core.getUpdateHandler().addDoc(aclTxCmd);
    
        AddUpdateCommand aclCmd = new AddUpdateCommand(solrQueryRequest);
        aclCmd.overwrite = true;
        SolrInputDocument aclSol = new SolrInputDocument();
        String aclDocId = AlfrescoSolrDataModel.getAclDocumentId(AlfrescoSolrDataModel.DEFAULT_TENANT, Long.valueOf(aclId));
        aclSol.addField(FIELD_SOLR4_ID, aclDocId);
        aclSol.addField(FIELD_VERSION, "0");
        aclSol.addField(FIELD_ACLID, aclId);
        aclSol.addField(FIELD_INACLTXID, "" + acltxid);
        aclSol.addField(FIELD_READER, "GROUP_EVERYONE");
        aclSol.addField(FIELD_READER, "pig");
        for (int i = 0; i <= maxReader; i++)
        {
            aclSol.addField(FIELD_READER, "READER-" + (totalReader - i));
        }
        aclSol.addField(FIELD_DENIED, "something");
        aclSol.addField(FIELD_DOC_TYPE, SolrDocTypeConstants.DOC_TYPE_ACL);
        aclCmd.solrDoc = aclSol;
        core.getUpdateHandler().addDoc(aclCmd);
    }

    /**
     * Basic wrapper class to create some simple Acl changesets
     */
    public static class TestActChanges {
        private AclChangeSet aclChangeSet;
        private Acl acl;
        private Acl acl2;

        public AclChangeSet getChangeSet() {
            return aclChangeSet;
        }

        public Acl getFirstAcl() {
            return acl;
        }
        public Acl getSecondAcl() {
            return acl2;
        }


        public TestActChanges createBasicTestData() {
            aclChangeSet = getAclChangeSet(1);

            acl = getAcl(aclChangeSet);
            acl2 = getAcl(aclChangeSet);

            AclReaders aclReaders = getAclReaders(aclChangeSet, acl, list("joel"), list("phil"), null);
            AclReaders aclReaders2 = getAclReaders(aclChangeSet, acl2, list("jim"), list("phil"), null);

            indexAclChangeSet(aclChangeSet,
                    list(acl, acl2),
                    list(aclReaders, aclReaders2));
            return this;
        }
    }


    /**
     * Gets a SolrCore by name without incrementing the internal counter
     * @param coreContainer
     * @param coreName
     * @return SolrCore
     */
    public static SolrCore getCore(CoreContainer coreContainer, String coreName)
    {
        return coreContainer.getCores().stream()
                            .filter(aCore ->coreName.equals(aCore.getName()))
                            .findFirst().get();
    }

    /**
     * Creates a core using the specified template
     * @param coreContainer
     * @param coreAdminHandler
     * @param coreName
     * @param templateName
     * @param shards
     * @param nodes
     * @param extraParams Any number of additional parameters in name value pairs.
     * @return
     * @throws InterruptedException
     */
    public static SolrCore createCoreUsingTemplate(CoreContainer coreContainer, CoreAdminHandler coreAdminHandler,
                                                   String coreName, String templateName, int shards, int nodes,
                                                   String... extraParams) throws InterruptedException {
        SolrCore testingCore = null;
        ModifiableSolrParams coreParams = params(CoreAdminParams.ACTION, "newcore",
                "storeRef", "workspace://SpacesStore",
                "coreName", coreName,
                "numShards", String.valueOf(shards),
                "nodeInstance", String.valueOf(nodes),
                "template", templateName);
        coreParams.add(params(extraParams));
        SolrQueryRequest request = new LocalSolrQueryRequest(null,coreParams);
        SolrQueryResponse response = new SolrQueryResponse();
        try {
            coreAdminHandler.handleRequestBody(request, response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create core using template", e);
        }
        TimeUnit.SECONDS.sleep(1);
        if(shards > 1 )
        {
            NamedList action = (NamedList) response.getValues().get("action");
            List<String> coreNames = action.getAll("core");
            assertEquals(shards,coreNames.size());
            testingCore = getCore(coreContainer, coreNames.get(0));
        }
        else
        {

            NamedList action = (NamedList) response.getValues().get("action");
            assertEquals(coreName, action.get("core"));
            //Get a reference to the new core
            testingCore = getCore(coreContainer, coreName);
        }

        TimeUnit.SECONDS.sleep(4); //Wait a little for background threads to catchup
        assertNotNull(testingCore);
        return testingCore;
    }

    /**
     * Asserts the summary report has been returned for the correct core and that the number of searchers is 1 or more.
     * @param response
     * @param coreName
     */
    public static void assertSummaryCorrect(SolrQueryResponse response, String coreName) {
        NamedList<Object> summary = (NamedList<Object>) response.getValues().get("Summary");
        assertNotNull(summary);
        NamedList<Object> coreSummary = (NamedList<Object>) summary.get(coreName);
        assertNotNull(coreSummary);
        assertTrue("There must be a searcher for "+coreName, ((Integer)coreSummary.get("Number of Searchers")) > 0);
    }

    public static CoreAdminHandler coreAdminHandler(SolrCore core) {
        return of(core).map(SolrCore::getCoreContainer)
                .map(CoreContainer::getMultiCoreHandler)
                .orElseThrow(() -> new IllegalStateException("Cannot retrieve the Core Admin Handler on this test core."));
    }
}