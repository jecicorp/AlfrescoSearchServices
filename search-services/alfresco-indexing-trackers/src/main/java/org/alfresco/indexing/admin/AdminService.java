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
package org.alfresco.indexing.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.ContentTracker;
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
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
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

    public Map<String, Object> summary(String core, String cores, String metrics)
    {
        Set<String> coreFilter = parseCsv(cores);
        Set<String> metricFilter = parseCsv(metrics);

        Map<String, Object> result = new LinkedHashMap<>();
        TrackerRegistry registry = trackerBootstrap.getRegistry();

        for (String coreName : coresToProcess(registry, core))
        {
            // Restrict to the explicitly requested cores, when a list is given
            if (!coreFilter.isEmpty() && !coreFilter.contains(coreName))
            {
                continue;
            }

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

                // Tracker stats: expose aggregated indexing-performance indicators as a
                // structured map. The previous trackerStats.toString() dumped a multi-KB blob
                // of Java object internals (per-thread histograms) as a single JSON string,
                // which drowned out the rest of the report and was unusable for monitoring.
                TrackerStats trackerStats = infoSrv.getTrackerStats();
                if (trackerStats != null)
                {
                    Map<String, Object> statsReport = new LinkedHashMap<>();
                    statsReport.put("MeanDocsPerTx", trackerStats.getMeanDocsPerTx());
                    statsReport.put("MeanAclsPerChangeSet", trackerStats.getMeanAclsPerChangeSet());
                    statsReport.put("NodeIndexingThreadCount", trackerStats.getNodeIndexingThreadCount());
                    statsReport.put("MeanModelSyncTimeMs", trackerStats.getMeanModelSyncTime());
                    statsReport.put("MeanNodeIndexTimeMs", trackerStats.getMeanNodeIndexTime());
                    statsReport.put("MeanNodeElapsedIndexTimeMs", trackerStats.getMeanNodeElapsedIndexTime());
                    statsReport.put("MeanAclElapsedIndexTimeMs", trackerStats.getMeanAclElapsedIndexTime());
                    statsReport.put("MeanContentElapsedIndexTimeMs", trackerStats.getMeanContentElapsedIndexTime());
                    coreReport.put("TrackerStats", statsReport);
                }

                // Stock-Alfresco-compatible SUMMARY fields. Tools that predate the fork's
                // restructured report (notably the OOTBee Support Tools "Solr Tracking"
                // page) read these classic AlfrescoCoreAdminHandler field names. Emitted
                // alongside the structured keys above so the report is a superset.
                ContentTracker contentTracker = registry.getTrackerForCore(coreName, ContentTracker.class);
                coreReport.put("MetadataTracker Active", metadataTracker != null && metadataTracker.isEnabled());
                coreReport.put("AclTracker Active", aclTracker != null && aclTracker.isEnabled());
                coreReport.put("ContentTracker Active", contentTracker != null && contentTracker.isEnabled());

                if (metadataTracker != null && metadataTracker.getTrackerState() != null)
                {
                    TrackerState s = metadataTracker.getTrackerState();
                    long txRemaining = Math.max(0, s.getLastTxIdOnServer() - s.getLastIndexedTxId());
                    long txMsLag = Math.max(0, s.getLastTxCommitTimeOnServer() - s.getLastIndexedTxCommitTime());
                    coreReport.put("Id for last TX in index", s.getLastIndexedTxId());
                    coreReport.put("Approx transactions remaining", txRemaining);
                    coreReport.put("TX Lag", (txMsLag / 1000) + " s");
                    coreReport.put("Approx transaction indexing time remaining", txRemaining == 0 ? "0 s" : "N/A");
                }
                else
                {
                    coreReport.put("Id for last TX in index", 0L);
                    coreReport.put("Approx transactions remaining", 0L);
                    coreReport.put("TX Lag", "0 s");
                    coreReport.put("Approx transaction indexing time remaining", "0 s");
                }

                if (aclTracker != null && aclTracker.getTrackerState() != null)
                {
                    TrackerState s = aclTracker.getTrackerState();
                    coreReport.put("Approx change sets remaining",
                            Math.max(0, s.getLastChangeSetIdOnServer() - s.getLastIndexedChangeSetId()));
                }
                else
                {
                    coreReport.put("Approx change sets remaining", 0L);
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error building summary for core {}", coreName, e);
                coreReport.put("error", e.getMessage());
            }

            // Keep only the requested metrics when a filter is given; full report otherwise
            result.put(coreName, filterMetrics(coreReport, metricFilter));
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
            // Discover Solr home from an existing core's instanceDir
            String solrHome = discoverSolrHome();
            if (solrHome == null)
            {
                result.put("status", "error");
                result.put("errorMessage", "Cannot discover Solr home — no existing core found");
                return result;
            }

            String effectiveTemplate = template != null ? template : "rerank";
            String configSetPath = solrHome + "/templates/" + effectiveTemplate;
            String instanceDir = solrHome + "/" + coreName;

            org.apache.solr.common.params.ModifiableSolrParams params = new org.apache.solr.common.params.ModifiableSolrParams();
            params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.CREATE.toString());
            params.set(CoreAdminParams.NAME, coreName);
            params.set(CoreAdminParams.INSTANCE_DIR, instanceDir);
            params.set("configSet", configSetPath);
            // Required properties for Alfresco Solr config
            String dataRoot = solrHome.replace("/solrhome", "/data");
            params.set("property.data.dir.root", dataRoot);
            params.set("property.data.dir.store", coreName);
            if (storeRef != null)
            {
                params.set("property.alfresco.stores", storeRef);
            }
            params.set("property.alfresco.template", effectiveTemplate);

            org.apache.solr.client.solrj.request.QueryRequest request =
                    new org.apache.solr.client.solrj.request.QueryRequest(params);
            request.setPath("/admin/cores");
            request.process(solrClient);
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

    /**
     * Solr-compat STATUS action. STATUS is a NATIVE Solr core-admin action (per-core
     * index doc counts and size), not an Alfresco control-plane action, so its data
     * lives in Solr, not in the trackers. It is served by proxying to the real Solr
     * core admin via SolrJ and returning a per-core map shaped like Solr's own
     * {@code status} object ({@code <core>.index.{numDocs,maxDoc,deletedDocs,...}}),
     * which is what tools such as the OOTBee Support Tools "Solr Tracking" page expect.
     *
     * @param core optional core name; when null/blank, all cores are returned
     */
    public Map<String, Object> status(String core)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        try
        {
            CoreAdminRequest statusRequest = new CoreAdminRequest();
            statusRequest.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            if (core != null && !core.isBlank())
            {
                statusRequest.setCoreName(core);
            }
            CoreAdminResponse response = statusRequest.process(solrClient);
            for (int i = 0; i < response.getCoreStatus().size(); i++)
            {
                String name = response.getCoreStatus().getName(i);
                NamedList<Object> coreData = response.getCoreStatus(name);
                if (name == null || coreData == null)
                {
                    continue;
                }
                result.put(name, buildCoreStatus(coreData));
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to retrieve Solr core STATUS", e);
            throw new RuntimeException("Failed to retrieve Solr core STATUS: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Flattens a single core's STATUS NamedList into a Map and guarantees the numeric
     * index fields the admin UI renders are present (defaulting to 0 when Solr omits
     * one, e.g. indexHeapUsageBytes on some Solr 9 builds), so the FreeMarker template
     * never hits a missing value.
     */
    private Map<String, Object> buildCoreStatus(NamedList<Object> coreData)
    {
        Map<String, Object> core = new LinkedHashMap<>();
        Map<String, Object> index = new LinkedHashMap<>();

        Object indexObj = coreData.get("index");
        if (indexObj instanceof NamedList<?> indexList)
        {
            for (int i = 0; i < indexList.size(); i++)
            {
                index.put(indexList.getName(i), indexList.getVal(i));
            }
        }
        index.putIfAbsent("numDocs", 0);
        index.putIfAbsent("maxDoc", 0);
        index.putIfAbsent("deletedDocs", 0);
        index.putIfAbsent("sizeInBytes", 0L);
        index.putIfAbsent("indexHeapUsageBytes", 0L);

        core.put("index", index);
        return core;
    }

    /**
     * Discovers the Solr home directory by querying an existing core's instanceDir.
     * Returns the parent directory (e.g. /opt/alfresco-search-services/solrhome).
     */
    private String discoverSolrHome()
    {
        try
        {
            org.apache.solr.client.solrj.request.CoreAdminRequest statusRequest =
                    new org.apache.solr.client.solrj.request.CoreAdminRequest();
            statusRequest.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            org.apache.solr.client.solrj.response.CoreAdminResponse statusResponse = statusRequest.process(solrClient);
            for (int i = 0; i < statusResponse.getCoreStatus().size(); i++)
            {
                String name = statusResponse.getCoreStatus().getName(i);
                String instanceDir = statusResponse.getCoreStatus(name) != null
                        ? (String) statusResponse.getCoreStatus(name).get("instanceDir") : null;
                if (instanceDir != null)
                {
                    // instanceDir is like /opt/.../solrhome/alfresco — parent is solrhome
                    java.io.File parent = new java.io.File(instanceDir).getParentFile();
                    return parent.getAbsolutePath();
                }
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to discover Solr home", e);
        }
        return null;
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

    /**
     * Parse a comma-separated parameter into an ordered set of trimmed,
     * non-empty values. Returns an empty set when the input is null or blank.
     */
    private static Set<String> parseCsv(String csv)
    {
        if (csv == null || csv.isBlank())
        {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : csv.split(","))
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                values.add(trimmed);
            }
        }
        return values;
    }

    /**
     * Keep only the report entries whose key matches one of the requested
     * metrics, using a case-insensitive substring match (so {@code tx} selects
     * TX, TXLag, AclTX... and {@code nodes} selects the node counts). Returns
     * the report unchanged when no metric filter is requested.
     */
    private static Map<String, Object> filterMetrics(Map<String, Object> coreReport, Set<String> metricFilter)
    {
        if (metricFilter.isEmpty())
        {
            return coreReport;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : coreReport.entrySet())
        {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String wanted : metricFilter)
            {
                if (key.contains(wanted.toLowerCase(Locale.ROOT)))
                {
                    filtered.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        return filtered;
    }
}
