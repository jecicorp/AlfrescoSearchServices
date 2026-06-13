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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.ActivatableTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Periodically scans for documents flagged with indexing errors and attempts
 * to repair them using the registered {@link RepairStrategy} chain.
 */
public class RepairTracker extends ActivatableTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RepairTracker.class);

    private static final Map<String, Semaphore> RUN_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private static final Map<String, Semaphore> WRITE_LOCK_BY_CORE = new ConcurrentHashMap<>();

    private final List<RepairStrategy> strategies;
    private final TrackerRegistry registry;
    private final int maxRetries;
    private final RepairReport report = new RepairReport();

    public RepairTracker(Properties p, SOLRAPIClient client, String coreName,
                         InformationServer informationServer, List<RepairStrategy> strategies,
                         TrackerRegistry registry, int maxRetries)
    {
        super(p, client, coreName, informationServer, Type.REPAIR);
        this.strategies = strategies;
        this.registry = registry;
        this.maxRetries = maxRetries;

        RUN_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
        WRITE_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
    }

    @Override
    public Semaphore getWriteLock()
    {
        return WRITE_LOCK_BY_CORE.get(coreName);
    }

    @Override
    public Semaphore getRunLock()
    {
        return RUN_LOCK_BY_CORE.get(coreName);
    }

    /**
     * Main repair cycle: finds documents with indexing errors and attempts repair.
     * Visibility widened to public so it can be invoked from tests and programmatic triggers.
     */
    @Override
    public void doTrack(String iterationId) throws Throwable
    {
        report.startCycle();

        List<TenantDbId> errorDocs = infoSrv.getDocsWithIndexingError();
        if (errorDocs == null || errorDocs.isEmpty())
        {
            LOGGER.trace("No documents with indexing errors found.");
            return;
        }

        LOGGER.info("{}-[CORE {}] Found {} documents with indexing errors",
                Thread.currentThread().getId(), coreName, errorDocs.size());

        MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
        Semaphore metadataWriteLock = (metadataTracker != null) ? metadataTracker.getWriteLock() : null;

        try
        {
            if (metadataWriteLock != null)
            {
                metadataWriteLock.acquire();
            }

            for (TenantDbId docRef : errorDocs)
            {
                checkShutdown();
                processErrorNode(docRef);
            }
        }
        finally
        {
            if (metadataWriteLock != null)
            {
                metadataWriteLock.release();
            }
        }

        infoSrv.commit();

        LOGGER.info("{}-[CORE {}] Repair cycle done: {} repaired, {} pending",
                Thread.currentThread().getId(), coreName,
                report.getRepairedThisCycle(), report.getTotalErrorNodes());
    }

    private void processErrorNode(TenantDbId docRef)
    {
        try
        {
            RepairResult result = null;

            for (RepairStrategy strategy : strategies)
            {
                result = strategy.tryRepair(null, docRef);
                if (result != null)
                {
                    break;
                }
            }

            if (result == null)
            {
                result = new RepairResult(docRef.dbId, false, "UNKNOWN",
                        "No repair strategy could handle this node");
            }

            report.recordResult(result);

            if (result.success())
            {
                infoSrv.clearIndexingError(docRef.dbId, docRef.tenant);
                LOGGER.info("[CORE {}] Repaired DBID={}: {}", coreName, docRef.dbId, result.message());
            }
            else
            {
                int attempts = report.getAttemptCount(docRef.dbId);
                if (attempts >= maxRetries)
                {
                    LOGGER.warn("[CORE {}] DBID={} permanently failed after {} attempts: {}",
                            coreName, docRef.dbId, attempts, result.message());
                    report.markPermanentlyFailed(docRef.dbId);
                    infoSrv.clearIndexingError(docRef.dbId, docRef.tenant);
                }
                else
                {
                    LOGGER.debug("[CORE {}] DBID={} repair attempt {}/{} failed: {}",
                            coreName, docRef.dbId, attempts, maxRetries, result.message());
                }
            }
        }
        catch (Exception e)
        {
            LOGGER.error("[CORE {}] Error processing DBID={}: {}",
                    coreName, docRef.dbId, e.getMessage(), e);
        }
    }

    public RepairReport getReport()
    {
        return report;
    }

    @Override
    public boolean hasMaintenance()
    {
        return false;
    }

    @Override
    public void maintenance()
    {
    }
}
