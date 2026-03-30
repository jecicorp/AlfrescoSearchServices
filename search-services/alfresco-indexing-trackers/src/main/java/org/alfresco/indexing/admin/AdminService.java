/*
 * #%L
 * Alfresco Indexing Trackers
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
package org.alfresco.indexing.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.Tracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.AclReport;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.solr.tracker.TrackerStats;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.common.params.CoreAdminParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Encapsulates all business logic for Solr admin actions.
 * Replaces the old {@code AlfrescoCoreAdminHandler} that was removed
 * when trackers were extracted into a standalone Spring Boot service.
 */
@Service
public class AdminService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminService.class);

    private final TrackerBootstrap trackerBootstrap;
    private final SolrClient solrClient;

    public AdminService(TrackerBootstrap trackerBootstrap, SolrClient solrClient)
    {
        this.trackerBootstrap = trackerBootstrap;
        this.solrClient = solrClient;
    }

    // ----------------------------------------------------------------
    // Group 1: Mutation actions
    // ----------------------------------------------------------------

    public Map<String, Object> purge(Long txId, Long acltxId, Long nodeId, Long aclId, String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
            AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);

            if (metadataTracker != null && metadataTracker.isEnabled())
            {
                if (txId != null) metadataTracker.addTransactionToPurge(txId);
                if (nodeId != null) metadataTracker.addNodeToPurge(nodeId);
                coreResult.put("status", "scheduled");
            }
            else
            {
                coreResult.put("status", "notScheduled");
            }

            if (aclTracker != null && aclTracker.isEnabled())
            {
                if (acltxId != null) aclTracker.addAclChangeSetToPurge(acltxId);
                if (aclId != null) aclTracker.addAclToPurge(aclId);
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> reindex(Long txId, Long acltxId, Long nodeId, Long aclId, String query, String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
            AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);

            if (metadataTracker != null && metadataTracker.isEnabled())
            {
                if (txId != null) metadataTracker.addTransactionToReindex(txId);
                if (nodeId != null) metadataTracker.addNodeToReindex(nodeId);
                if (query != null) metadataTracker.addQueryToReindex(query);
                coreResult.put("status", "scheduled");
            }
            else
            {
                coreResult.put("status", "notScheduled");
            }

            if (aclTracker != null && aclTracker.isEnabled())
            {
                if (acltxId != null) aclTracker.addAclChangeSetToReindex(acltxId);
                if (aclId != null) aclTracker.addAclToReindex(aclId);
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> retry(String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();
            InformationServer infoSrv = trackerBootstrap.getInformationServer(coreName);

            try
            {
                Set<Long> errorDocIds = infoSrv.getErrorDocIds();
                MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);

                if (metadataTracker != null && metadataTracker.isEnabled())
                {
                    for (Long errorDocId : errorDocIds)
                    {
                        metadataTracker.addNodeToReindex(errorDocId);
                    }
                    coreResult.put("status", "scheduled");
                    coreResult.put("Error Nodes", new ArrayList<>(errorDocIds));
                }
                else
                {
                    coreResult.put("status", "notScheduled");
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error retrieving error doc IDs for core {}", coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> index(Long txId, Long acltxId, Long nodeId, Long aclId, String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
            AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);

            if (metadataTracker != null && metadataTracker.isEnabled())
            {
                if (txId != null) metadataTracker.addTransactionToIndex(txId);
                if (nodeId != null) metadataTracker.addNodeToIndex(nodeId);
                coreResult.put("status", "scheduled");
            }
            else
            {
                coreResult.put("status", "notScheduled");
            }

            if (aclTracker != null && aclTracker.isEnabled())
            {
                if (acltxId != null) aclTracker.addAclChangeSetToIndex(acltxId);
                if (aclId != null) aclTracker.addAclToIndex(aclId);
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> check(String core)
    {
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Collection<Tracker> trackers = registry.getTrackersForCore(coreName);
            for (Tracker tracker : trackers)
            {
                TrackerState state = tracker.getTrackerState();
                if (state != null)
                {
                    state.setCheck(true);
                }
            }
        }

        return Map.of("status", "success");
    }

    public Map<String, Object> log4j(String resource)
    {
        if (resource != null && !"log4j.properties".equals(resource))
        {
            return Map.of("status", "error");
        }
        // No-op in standalone mode
        return Map.of("status", "success");
    }

    // ----------------------------------------------------------------
    // Group 2: Report actions
    // ----------------------------------------------------------------

    public Map<String, Object> summary(String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreReport = new LinkedHashMap<>();
            InformationServer infoSrv = trackerBootstrap.getInformationServer(coreName);

            try
            {
                // Core stats
                Iterable<Map.Entry<String, Object>> coreStats = infoSrv.getCoreStats();
                for (Map.Entry<String, Object> entry : coreStats)
                {
                    coreReport.put(entry.getKey(), entry.getValue());
                }

                // Content outdated/updated counts (wrapped in FTS sub-map)
                Map<String, Object> ftsMap = new LinkedHashMap<>();
                infoSrv.addContentOutdatedAndUpdatedCounts(ftsMap);
                coreReport.put("FTS", ftsMap);

                // Model errors
                Map<String, Set<String>> modelErrors = infoSrv.getModelErrors();
                if (modelErrors != null && !modelErrors.isEmpty())
                {
                    coreReport.put("ModelErrors", modelErrors);
                }

                // Tracker states
                MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
                if (metadataTracker != null)
                {
                    TrackerState txState = metadataTracker.getTrackerState();
                    if (txState != null)
                    {
                        Map<String, Object> txReport = new LinkedHashMap<>();
                        txReport.put("Id", txState.getLastIndexedTxId());
                        txReport.put("CommitTime", txState.getLastIndexedTxCommitTime());
                        txReport.put("IdOnServer", txState.getLastTxIdOnServer());
                        txReport.put("CommitTimeOnServer", txState.getLastTxCommitTimeOnServer());
                        txReport.put("IdBeforeHoles", txState.getLastIndexedTxIdBeforeHoles());
                        coreReport.put("TX", txReport);

                        long txLag = txState.getLastTxIdOnServer() - txState.getLastIndexedTxId();
                        coreReport.put("TXLag", txLag);
                        long txTimeLag = txState.getLastTxCommitTimeOnServer() - txState.getLastIndexedTxCommitTime();
                        coreReport.put("TXDurationLag", txTimeLag);
                    }
                }

                AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);
                if (aclTracker != null)
                {
                    TrackerState aclState = aclTracker.getTrackerState();
                    if (aclState != null)
                    {
                        Map<String, Object> aclReport = new LinkedHashMap<>();
                        aclReport.put("Id", aclState.getLastIndexedChangeSetId());
                        aclReport.put("CommitTime", aclState.getLastIndexedChangeSetCommitTime());
                        aclReport.put("IdOnServer", aclState.getLastChangeSetIdOnServer());
                        aclReport.put("CommitTimeOnServer", aclState.getLastChangeSetCommitTimeOnServer());
                        aclReport.put("IdBeforeHoles", aclState.getLastIndexedChangeSetIdBeforeHoles());
                        coreReport.put("AclTX", aclReport);

                        long aclLag = aclState.getLastChangeSetIdOnServer() - aclState.getLastIndexedChangeSetId();
                        coreReport.put("AclTXLag", aclLag);
                        long aclTimeLag = aclState.getLastChangeSetCommitTimeOnServer() - aclState.getLastIndexedChangeSetCommitTime();
                        coreReport.put("AclTXDurationLag", aclTimeLag);
                    }
                }

                // Tracker stats
                TrackerStats trackerStats = infoSrv.getTrackerStats();
                if (trackerStats != null)
                {
                    coreReport.put("TrackerStats", trackerStats.toString());
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error building summary for core {}", coreName, e);
                coreReport.put("error", e.getMessage());
            }

            result.put(coreName, coreReport);
        }

        return result;
    }

    public Map<String, Object> nodeReport(Long nodeId, String core)
    {
        if (nodeId == null)
        {
            return Map.of("error", "No nodeid parameter set.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            try
            {
                MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
                if (metadataTracker != null)
                {
                    NodeReport report = metadataTracker.checkNode(nodeId);
                    if (report != null)
                    {
                        coreResult.put("Node DBID", report.getDbid());
                        coreResult.put("dbTx", report.getDbTx());
                        coreResult.put("dbNodeStatus", report.getDbNodeStatus());
                        coreResult.put("indexLeafDoc", report.getIndexLeafDoc());
                        coreResult.put("indexAuxDoc", report.getIndexAuxDoc());
                        coreResult.put("indexLeafTx", report.getIndexLeafTx());
                        coreResult.put("indexAuxTx", report.getIndexAuxTx());
                        coreResult.put("indexedNodeDocCount", report.getIndexedNodeDocCount());
                    }
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error checking node {} on core {}", nodeId, coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> aclReport(Long aclId, String core)
    {
        if (aclId == null)
        {
            return Map.of("error", "No aclid parameter set.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            try
            {
                AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);
                if (aclTracker != null)
                {
                    AclReport report = aclTracker.checkAcl(aclId);
                    if (report != null)
                    {
                        coreResult.put("Acl Id", report.getAclId());
                        coreResult.put("existsInDb", report.isExistsInDb());
                        coreResult.put("indexAclDoc", report.getIndexAclDoc());
                        coreResult.put("indexAclTx", report.getIndexAclTx());
                        coreResult.put("indexedAclDocCount", report.getIndexedAclDocCount());
                    }
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error checking ACL {} on core {}", aclId, coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> txReport(Long txId, String core)
    {
        if (txId == null)
        {
            return Map.of("error", "No txid parameter set.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            try
            {
                MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
                if (metadataTracker != null)
                {
                    List<Node> nodes = metadataTracker.getFullNodesForDbTransaction(txId);
                    coreResult.put("TXID", txId);
                    coreResult.put("nodeCount", nodes != null ? nodes.size() : 0);
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error fetching transaction {} on core {}", txId, coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> aclTxReport(Long aclTxId, String core)
    {
        if (aclTxId == null)
        {
            return Map.of("error", "No acltxid parameter set.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            try
            {
                AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);
                if (aclTracker != null)
                {
                    List<Long> acls = aclTracker.getAclsForDbAclTransaction(aclTxId);
                    coreResult.put("aclTxId", aclTxId);
                    coreResult.put("aclTxDbAclCount", acls != null ? acls.size() : 0);
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error fetching ACL transaction {} on core {}", aclTxId, coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    public Map<String, Object> report(String core, Long fromTime, Long toTime)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            Map<String, Object> coreResult = new LinkedHashMap<>();

            try
            {
                MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
                AclTracker aclTracker = registry.getTrackerForCore(coreName, AclTracker.class);

                if (metadataTracker != null)
                {
                    TrackerState txState = metadataTracker.getTrackerState();
                    Long toTx = txState != null ? txState.getLastIndexedTxId() : null;
                    IndexHealthReport txReport = metadataTracker.checkIndex(toTx, fromTime, toTime);
                    if (txReport != null)
                    {
                        coreResult.put("DB transaction count", txReport.getDbTransactionCount());
                        coreResult.put("Transaction docs in index", txReport.getTransactionDocsInIndex());
                        coreResult.put("Unique transaction docs in index", txReport.getUniqueTransactionDocsInIndex());
                        coreResult.put("Leaf doc count in index", txReport.getLeafDocCountInIndex());
                        coreResult.put("Aux doc count in index", txReport.getAuxDocCountInIndex());
                        coreResult.put("Error doc count in index", txReport.getErrorDocCountInIndex());
                        coreResult.put("Unindexed doc count in index", txReport.getUnindexedDocCountInIndex());
                        coreResult.put("Count of missing transactions from the Index", txReport.getMissingTxFromIndex().cardinality());
                        coreResult.put("Count of duplicated transactions in the Index", txReport.getDuplicatedTxInIndex().cardinality());
                        coreResult.put("Count of transactions in the index but not the DB", txReport.getTxInIndexButNotInDb().cardinality());
                        coreResult.put("Count of duplicated leaf nodes in the Index", txReport.getDuplicatedLeafInIndex().cardinality());
                        coreResult.put("Count of duplicated aux nodes in the Index", txReport.getDuplicatedAuxInIndex().cardinality());
                        coreResult.put("Last indexed commit time", txReport.getLastIndexedCommitTime());
                        coreResult.put("Last indexed id before holes", txReport.getLastIndexedIdBeforeHoles());
                    }
                }

                if (aclTracker != null)
                {
                    TrackerState aclState = aclTracker.getTrackerState();
                    Long toAclTx = aclState != null ? aclState.getLastIndexedChangeSetId() : null;
                    IndexHealthReport aclReport = aclTracker.checkIndex(toAclTx, fromTime, toTime);
                    if (aclReport != null)
                    {
                        coreResult.put("DB acl transaction count", aclReport.getDbAclTransactionCount());
                        coreResult.put("Acl transaction docs in index", aclReport.getAclTransactionDocsInIndex());
                        coreResult.put("Unique acl transaction docs in index", aclReport.getUniqueAclTransactionDocsInIndex());
                        coreResult.put("Count of missing acl transactions from the Index", aclReport.getMissingAclTxFromIndex().cardinality());
                        coreResult.put("Count of duplicated acl transactions in the Index", aclReport.getDuplicatedAclTxInIndex().cardinality());
                        coreResult.put("Count of acl transactions in the index but not the DB", aclReport.getAclTxInIndexButNotInDb().cardinality());
                    }
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error building report for core {}", coreName, e);
                coreResult.put("error", e.getMessage());
            }

            result.put(coreName, coreResult);
        }

        return result;
    }

    // ----------------------------------------------------------------
    // Group 3: Core management
    // ----------------------------------------------------------------

    public Map<String, Object> newCore(String coreName, String storeRef, String template)
    {
        Map<String, Object> result = new LinkedHashMap<>();

        try
        {
            CoreAdminRequest.Create createRequest = new CoreAdminRequest.Create();
            createRequest.setCoreName(coreName);
            if (template != null)
            {
                createRequest.setConfigSet(template);
            }
            if (storeRef != null)
            {
                createRequest.setCoreNodeName(storeRef);
            }
            createRequest.process(solrClient);
            result.put("status", "success");
            result.put("core", coreName);
        }
        catch (Exception e)
        {
            LOGGER.error("Error creating core {}", coreName, e);
            result.put("status", "error");
            result.put("errorMessage", e.getMessage());
        }

        return result;
    }

    public Map<String, Object> updateCore(String coreName)
    {
        Map<String, Object> result = new LinkedHashMap<>();

        try
        {
            CoreAdminRequest.reloadCore(coreName, solrClient);
            result.put("status", "success");
            result.put("core", coreName);
        }
        catch (Exception e)
        {
            LOGGER.error("Error reloading core {}", coreName, e);
            result.put("status", "error");
            result.put("errorMessage", e.getMessage());
        }

        return result;
    }

    public Map<String, Object> updateShared()
    {
        // No-op in standalone mode
        return Map.of("status", "success");
    }

    public Map<String, Object> newDefaultIndex(String coreName, String storeRef, String template)
    {
        return newCore(coreName, storeRef, template);
    }

    public Map<String, Object> removeCore(String coreName, String storeRef)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        try
        {
            // Stop trackers for this core
            Collection<Tracker> trackers = registry.getTrackersForCore(coreName);
            for (Tracker tracker : trackers)
            {
                if (!tracker.isAlreadyInShutDownMode())
                {
                    tracker.setShutdown(true);
                    tracker.shutdown();
                }
            }
            registry.removeTrackersForCore(coreName);

            // Unload core from Solr
            CoreAdminRequest.unloadCore(coreName, solrClient);
            result.put("status", "success");
            result.put("core", coreName);
        }
        catch (Exception e)
        {
            LOGGER.error("Error removing core {}", coreName, e);
            result.put("status", "error");
            result.put("errorMessage", e.getMessage());
        }

        return result;
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private Set<String> coresToProcess(TrackerRegistry registry, String core)
    {
        if (core != null && !core.isBlank())
        {
            return Set.of(core);
        }
        return registry.getCoreNames();
    }
}
