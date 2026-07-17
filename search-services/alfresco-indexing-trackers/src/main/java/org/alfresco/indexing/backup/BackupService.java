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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Triggers Solr index backup/restore through the standalone ReplicationHandler
 * ({@code /<core>/replication?command=backup|restore}). Solr writes the snapshot
 * to its own filesystem; the path must be inside {@code solr.allowPaths}.
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
        return execute(coreName, params, "backup");
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
        return execute(coreName, params, "restore");
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
}
