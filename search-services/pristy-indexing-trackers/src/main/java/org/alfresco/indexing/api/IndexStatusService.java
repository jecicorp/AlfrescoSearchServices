/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.api;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ERROR_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_UNINDEXED_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_ACLID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DBID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DOC_TYPE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_HAS_INDEXING_ERROR;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_INTXID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_LID;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.alfresco.indexing.api.NodeIndexStatus.Core;
import org.alfresco.indexing.api.NodeIndexStatus.Database;
import org.alfresco.indexing.api.NodeIndexStatus.DatabaseStatus;
import org.alfresco.indexing.api.NodeIndexStatus.Reference;
import org.alfresco.indexing.api.NodeIndexStatus.State;
import org.alfresco.indexing.api.NodeIndexStatus.Verdict;
import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers "is this node indexed, and is the index up to date for it" in one
 * call, from the index and the repository database at once.
 *
 * <p>Unlike the Solr-compat {@code NODEREPORT} action, which this service does
 * not use, the answer is a verdict rather than a set of raw fields, and the
 * node may be designated by DBID, UUID or node reference.
 */
@Service
public class IndexStatusService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexStatusService.class);

    private static final Pattern DBID_PATTERN = Pattern.compile("\\d{1,18}");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final List<String> UUID_STORES = List.of("workspace://SpacesStore/", "archive://SpacesStore/");

    private static final String RESOLVED_BY_DBID = "DBID";
    private static final String RESOLVED_BY_LID = "LID";

    private static final long TX_NO_SUCH_NODE = -1L;
    private static final String INDEXING_ERROR_FLAG = "true";

    private static final List<Verdict> VERDICT_PRECEDENCE = List.of(
            Verdict.ERROR, Verdict.ORPHAN, Verdict.STALE, Verdict.UNVERIFIED,
            Verdict.UNINDEXED, Verdict.INDEXED);

    private final TrackerBootstrap trackerBootstrap;
    private final SolrClient solrClient;

    public IndexStatusService(TrackerBootstrap trackerBootstrap, SolrClient solrClient)
    {
        this.trackerBootstrap = trackerBootstrap;
        this.solrClient = solrClient;
    }

    /**
     * @param reference a node DBID, a node UUID, or a full node reference
     * @return the node's indexing status across every tracked core
     * @throws IllegalArgumentException when the reference is blank or malformed
     */
    public NodeIndexStatus status(String reference)
    {
        String input = reference == null ? "" : reference.trim();
        if (input.isEmpty())
        {
            throw new IllegalArgumentException("No node reference supplied.");
        }

        TrackerRegistry registry = trackerBootstrap.getRegistry();
        List<String> coreNames = List.copyOf(registry.getCoreNames());

        Long dbid = DBID_PATTERN.matcher(input).matches() ? Long.valueOf(input) : null;
        String resolvedBy = dbid != null ? RESOLVED_BY_DBID : null;
        if (dbid == null)
        {
            dbid = resolveByNodeRef(input, coreNames);
            resolvedBy = dbid != null ? RESOLVED_BY_LID : null;
        }

        if (dbid == null)
        {
            return new NodeIndexStatus(new Reference(input, null, null),
                    new Database(DatabaseStatus.UNKNOWN, null), Map.of(), Verdict.UNRESOLVED);
        }

        Database database = resolveDatabase(registry, coreNames, dbid);
        Map<String, Core> cores = new LinkedHashMap<>(coreNames.size());
        for (String coreName : coreNames)
        {
            cores.put(coreName, coreStatus(coreName, dbid, database));
        }

        return new NodeIndexStatus(new Reference(input, dbid, resolvedBy), database, cores, verdict(cores));
    }

    private Long resolveByNodeRef(String input, List<String> coreNames)
    {
        String clause = nodeRefClause(input);
        for (String coreName : coreNames)
        {
            SolrQuery query = luceneQuery(clause + " AND " + FIELD_DOC_TYPE + ":" + DOC_TYPE_NODE);
            query.setRows(1);
            query.setFields(FIELD_DBID);
            SolrDocumentList docs = search(coreName, query);
            if (docs != null && !docs.isEmpty())
            {
                Long dbid = longValue(docs.get(0), FIELD_DBID);
                if (dbid != null)
                {
                    return Math.abs(dbid);
                }
            }
        }
        return null;
    }

    private static String nodeRefClause(String input)
    {
        if (input.contains("://"))
        {
            return FIELD_LID + ":\"" + input + "\"";
        }
        if (!UUID_PATTERN.matcher(input).matches())
        {
            throw new IllegalArgumentException(
                    "Expected a numeric DBID, a node UUID, or a node reference of the form store://Store/<uuid>.");
        }
        List<String> clauses = new ArrayList<>(UUID_STORES.size());
        for (String store : UUID_STORES)
        {
            clauses.add("\"" + store + input + "\"");
        }
        return FIELD_LID + ":(" + String.join(" OR ", clauses) + ")";
    }

    private Database resolveDatabase(TrackerRegistry registry, List<String> coreNames, long dbid)
    {
        Database best = null;
        for (String coreName : coreNames)
        {
            NodeReport report = databaseReport(registry, coreName, dbid);
            if (report == null)
            {
                continue;
            }
            Database database = database(report);
            if (database.status() == DatabaseStatus.UPDATED
                    || database.status() == DatabaseStatus.DELETED)
            {
                return database;
            }
            if (best == null || best.status() == DatabaseStatus.UNREACHABLE)
            {
                best = database;
            }
        }
        return best != null ? best : new Database(DatabaseStatus.UNREACHABLE, null);
    }

    private NodeReport databaseReport(TrackerRegistry registry, String coreName, long dbid)
    {
        try
        {
            MetadataTracker tracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
            return tracker == null ? null : tracker.checkNode(dbid);
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Could not read node {} from the repository through core {}", dbid, coreName, e);
            return null;
        }
    }

    private static Database database(NodeReport report)
    {
        SolrApiNodeStatus status = report.getDbNodeStatus();
        Long tx = report.getDbTx();
        if (tx != null && tx < TX_NO_SUCH_NODE)
        {
            return new Database(DatabaseStatus.UNREACHABLE, null);
        }
        if (status == null || status == SolrApiNodeStatus.UNKNOWN)
        {
            return new Database(DatabaseStatus.UNKNOWN, null);
        }
        if (status == SolrApiNodeStatus.DELETED || status == SolrApiNodeStatus.NON_SHARD_DELETED)
        {
            return new Database(DatabaseStatus.DELETED, tx);
        }
        return new Database(DatabaseStatus.UPDATED, tx);
    }

    private Core coreStatus(String coreName, long dbid, Database database)
    {
        SolrQuery query = luceneQuery(FIELD_DBID + ":" + dbid + " AND " + FIELD_DOC_TYPE + ":("
                + DOC_TYPE_NODE + " OR " + DOC_TYPE_ERROR_NODE + " OR " + DOC_TYPE_UNINDEXED_NODE + ")");
        query.setRows(10);
        query.setFields(FIELD_DOC_TYPE, FIELD_INTXID, FIELD_ACLID, FIELD_HAS_INDEXING_ERROR);
        SolrDocumentList docs = search(coreName, query);
        if (docs == null)
        {
            return new Core(State.UNVERIFIED, null, null, null, 0L);
        }
        if (docs.isEmpty())
        {
            return new Core(State.ABSENT, null, null, null, 0L);
        }

        SolrDocument document = preferred(docs);
        String docType = stringValue(document, FIELD_DOC_TYPE);
        Long indexTx = longValue(document, FIELD_INTXID);
        Long aclId = longValue(document, FIELD_ACLID);
        boolean indexingError =
                INDEXING_ERROR_FLAG.equals(stringValue(document, FIELD_HAS_INDEXING_ERROR));
        return new Core(state(docType, indexTx, indexingError, database), docType, indexTx, aclId,
                docs.getNumFound());
    }

    /**
     * Picks the document that decides the state when a core holds several for one node. An
     * {@code ErrorNode} wins: the indexer deletes it on every successful attempt, so its
     * presence means the last attempt failed.
     */
    private static SolrDocument preferred(SolrDocumentList docs)
    {
        for (SolrDocument document : docs)
        {
            if (DOC_TYPE_ERROR_NODE.equals(stringValue(document, FIELD_DOC_TYPE)))
            {
                return document;
            }
        }
        return docs.get(0);
    }

    private static State state(String docType, Long indexTx, boolean indexingError, Database database)
    {
        if (DOC_TYPE_ERROR_NODE.equals(docType) || indexingError)
        {
            return State.ERROR;
        }
        if (DOC_TYPE_UNINDEXED_NODE.equals(docType))
        {
            return State.UNINDEXED;
        }
        if (!DOC_TYPE_NODE.equals(docType))
        {
            return State.ABSENT;
        }
        if (database.status() == DatabaseStatus.DELETED
                || database.status() == DatabaseStatus.UNKNOWN)
        {
            return State.ORPHAN;
        }
        if (database.tx() == null || indexTx == null)
        {
            return State.UNVERIFIED;
        }
        return indexTx.equals(database.tx()) ? State.INDEXED : State.STALE;
    }

    private static Verdict verdict(Map<String, Core> cores)
    {
        for (Verdict candidate : VERDICT_PRECEDENCE)
        {
            for (Core core : cores.values())
            {
                if (candidate == verdictOf(core.state()))
                {
                    return candidate;
                }
            }
        }
        return Verdict.MISSING;
    }

    private static Verdict verdictOf(State state)
    {
        return switch (state)
        {
            case ERROR -> Verdict.ERROR;
            case ORPHAN -> Verdict.ORPHAN;
            case STALE -> Verdict.STALE;
            case UNVERIFIED -> Verdict.UNVERIFIED;
            case UNINDEXED -> Verdict.UNINDEXED;
            case INDEXED -> Verdict.INDEXED;
            case ABSENT -> null;
        };
    }

    private SolrDocumentList search(String coreName, SolrQuery query)
    {
        try
        {
            QueryResponse response = solrClient.query(coreName, query);
            return response.getResults();
        }
        catch (Exception e)
        {
            LOGGER.warn("Index status query failed on core {}", coreName, e);
            return null;
        }
    }

    private static SolrQuery luceneQuery(String q)
    {
        SolrQuery query = new SolrQuery(q);
        query.set("defType", "lucene");
        query.set("qt", "/query");
        return query;
    }

    private static String stringValue(SolrDocument document, String field)
    {
        Object value = document.getFieldValue(field);
        return value == null ? null : value.toString();
    }

    private static Long longValue(SolrDocument document, String field)
    {
        Object value = document.getFieldValue(field);
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        if (value == null)
        {
            return null;
        }
        try
        {
            return Long.valueOf(value.toString());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
