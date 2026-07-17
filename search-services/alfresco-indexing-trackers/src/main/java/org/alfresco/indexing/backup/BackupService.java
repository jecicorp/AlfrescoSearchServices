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
package org.alfresco.indexing.backup;

import java.util.LinkedHashMap;
import java.util.Map;

import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.config.TrackerProperties.ResolvedCoreConfig;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Triggers Solr index backup/restore through the standalone ReplicationHandler
 * ({@code /<core>/replication?command=backup|restore}). Solr writes the snapshot
 * to its own filesystem; the path must be inside {@code solr.allowPaths}.
 *
 * <p>Both commands are ASYNCHRONOUS in Solr: the trigger response only means the
 * command was accepted. This service therefore polls the ReplicationHandler
 * ({@code command=details} for backup, {@code command=restorestatus} for
 * restore) until the operation succeeds, fails, or the configured poll timeout
 * elapses — a returned {@code status: ok} means the operation COMPLETED.</p>
 *
 * <p>This service is strictly per-core: multi-core fan-out and core-name
 * resolution belong to the callers ({@code AdminService} iterates the tracker
 * registry, {@code BackupScheduler} iterates the configured collections).</p>
 */
@Service
public class BackupService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupService.class);

    private final SolrClient solrClient;
    private final TrackerProperties props;

    public BackupService(SolrClient solrClient, TrackerProperties props)
    {
        this.solrClient = solrClient;
        this.props = props;
    }

    /**
     * Backs up one core. Null {@code location}/{@code numberToKeep} fall back to
     * the resolved per-core backup configuration.
     */
    public Map<String, Object> backupCore(String coreName, String location, Integer numberToKeep)
    {
        ResolvedCoreConfig cfg = props.resolvedCore(coreName);
        String loc = location != null ? location : cfg.getBackupLocation();
        Integer keep = numberToKeep != null ? numberToKeep : cfg.getBackupNumberToKeep();

        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("command", "backup");
        params.set("location", loc + "/" + coreName);
        if (keep != null)
        {
            params.set("numberToKeep", String.valueOf(keep));
        }

        // The ReplicationHandler only publishes the "backup" details section
        // when a snapshot attempt COMPLETES (successfully or not), so capture
        // the pre-trigger entry to tell a fresh outcome from a stale one.
        NamedList<?> baseline = fetchBackupDetails(coreName);
        Map<String, Object> result = execute(coreName, params, "backup");
        if (!"ok".equals(result.get("status")))
        {
            return result;
        }
        return awaitBackupOutcome(coreName, baseline, result);
    }

    /**
     * Restores one core from a snapshot. When {@code location} is null it falls
     * back to the resolved per-core backup location; {@code name} omitted
     * restores the latest snapshot.
     */
    public Map<String, Object> restoreCore(String coreName, String location, String name)
    {
        String loc = location != null ? location : props.resolvedCore(coreName).getBackupLocation();
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("command", "restore");
        params.set("location", loc + "/" + coreName);
        if (name != null)
        {
            params.set("name", name);
        }

        Map<String, Object> result = execute(coreName, params, "restore");
        if (!"ok".equals(result.get("status")))
        {
            return result;
        }
        return awaitRestoreOutcome(coreName, result);
    }

    private Map<String, Object> execute(String coreName, ModifiableSolrParams params, String op)
    {
        Map<String, Object> coreResult = new LinkedHashMap<>();
        try
        {
            QueryRequest request = new QueryRequest(params);
            request.setPath("/" + coreName + "/replication");
            request.process(solrClient);
            coreResult.put("status", "ok");
            coreResult.put("location", params.get("location"));
            LOGGER.info("Solr {} triggered for core '{}' at {}", op, coreName, params.get("location"));
        }
        catch (Exception e)
        {
            LOGGER.error("Solr {} failed for core '{}'", op, coreName, e);
            coreResult.put("status", "error");
            coreResult.put("errorMessage", e.getMessage());
        }
        return coreResult;
    }

    /**
     * Polls {@code command=details} until the "backup" section differs from the
     * pre-trigger baseline: the SnapShooter publishes it only when the snapshot
     * attempt completes (with {@code status=success} or an {@code exception}).
     */
    private Map<String, Object> awaitBackupOutcome(String coreName, NamedList<?> baseline, Map<String, Object> result)
    {
        String baselineKey = String.valueOf(baseline);
        long deadline = System.currentTimeMillis() + props.getBackup().getPollTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            NamedList<?> details = fetchBackupDetails(coreName);
            if (details != null && !String.valueOf(details).equals(baselineKey))
            {
                Object exception = details.get("exception");
                Object status = details.get("status");
                if (exception != null || "failed".equals(status))
                {
                    Object cause = exception != null ? exception : status;
                    LOGGER.error("Solr backup failed for core '{}': {}", coreName, cause);
                    result.put("status", "error");
                    result.put("errorMessage", String.valueOf(cause));
                    return result;
                }
                if ("success".equals(status))
                {
                    result.put("snapshotName", details.get("snapshotName"));
                    LOGGER.info("Solr backup completed for core '{}': snapshot '{}'",
                            coreName, details.get("snapshotName"));
                    return result;
                }
            }
            if (!sleep(props.getBackup().getPollIntervalMillis()))
            {
                break;
            }
        }
        return timedOut(coreName, result, "backup", "command=details");
    }

    /**
     * Polls {@code command=restorestatus} until the restore triggered above is
     * done. The status reliably tracks OUR restore: triggering replaced the
     * handler's restore future before the trigger response returned.
     */
    private Map<String, Object> awaitRestoreOutcome(String coreName, Map<String, Object> result)
    {
        long deadline = System.currentTimeMillis() + props.getBackup().getPollTimeoutSeconds() * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            NamedList<?> status = fetchRestoreStatus(coreName);
            Object state = status != null ? status.get("status") : null;
            if ("success".equals(state))
            {
                result.put("snapshotName", status.get("snapshotName"));
                LOGGER.info("Solr restore completed for core '{}': snapshot '{}'",
                        coreName, status.get("snapshotName"));
                return result;
            }
            if ("failed".equals(state))
            {
                Object exception = status.get("exception");
                Object cause = exception != null ? exception : "restore failed";
                LOGGER.error("Solr restore failed for core '{}': {}", coreName, cause);
                result.put("status", "error");
                result.put("errorMessage", String.valueOf(cause));
                return result;
            }
            // "In Progress" (or not published yet): keep polling.
            if (!sleep(props.getBackup().getPollIntervalMillis()))
            {
                break;
            }
        }
        return timedOut(coreName, result, "restore", "command=restorestatus");
    }

    private NamedList<?> fetchBackupDetails(String coreName)
    {
        NamedList<?> details = fetchReplicationSection(coreName, "details");
        if (details != null && details.get("backup") instanceof NamedList<?> backup)
        {
            return backup;
        }
        return null;
    }

    private NamedList<?> fetchRestoreStatus(String coreName)
    {
        return fetchReplicationSection(coreName, "restorestatus");
    }

    private NamedList<?> fetchReplicationSection(String coreName, String command)
    {
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("command", command);
            QueryRequest request = new QueryRequest(params);
            request.setPath("/" + coreName + "/replication");
            NamedList<Object> response = solrClient.request(request);
            if (response != null && response.get(command) instanceof NamedList<?> section)
            {
                return section;
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Could not read replication '{}' for core '{}'", command, coreName, e);
        }
        return null;
    }

    private Map<String, Object> timedOut(String coreName, Map<String, Object> result, String op, String statusCommand)
    {
        LOGGER.warn("Solr {} for core '{}' still running after {}s; check /{}/replication?{}",
                op, coreName, props.getBackup().getPollTimeoutSeconds(), coreName, statusCommand);
        result.put("status", "inProgress");
        result.put("message", op + " still running after " + props.getBackup().getPollTimeoutSeconds()
                + "s; check /" + coreName + "/replication?" + statusCommand);
        return result;
    }

    private boolean sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
