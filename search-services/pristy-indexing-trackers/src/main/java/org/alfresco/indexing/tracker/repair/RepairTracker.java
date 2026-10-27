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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.ActivatableTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    private static final Duration CONTENT_RETRY_BASE_DELAY = Duration.ofMinutes(1);
    private static final int CONTENT_RETRY_BATCH_SIZE = 100;

    private record ContentRetry(int attempts, Instant nextAttempt) {}

    private final List<RepairStrategy> strategies;
    private final RepairStrategy contentStrategy;
    private final TrackerRegistry registry;
    private final int maxRetries;
    private final RepairReport report = new RepairReport();
    private final Map<Long, ContentRetry> contentRetries = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public RepairTracker(Properties p, SOLRAPIClient client, String coreName,
                         InformationServer informationServer, List<RepairStrategy> strategies,
                         RepairStrategy contentStrategy, TrackerRegistry registry, int maxRetries)
    {
        super(p, client, coreName, informationServer, Type.REPAIR);
        this.strategies = strategies;
        this.contentStrategy = contentStrategy;
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

        List<TenantDbId> flaggedDocs = infoSrv.getDocsWithIndexingError();
        List<TenantDbId> errorNodeDocs = infoSrv.getErrorNodeDocs();
        List<TenantDbId> transformFailedDocs = infoSrv.getDocsWithContentTransformFailure();
        flaggedDocs = flaggedDocs != null ? flaggedDocs : List.of();
        errorNodeDocs = errorNodeDocs != null ? errorNodeDocs : List.of();
        transformFailedDocs = transformFailedDocs != null ? transformFailedDocs : List.of();

        if (flaggedDocs.isEmpty() && errorNodeDocs.isEmpty() && transformFailedDocs.isEmpty())
        {
            LOGGER.trace("No documents with indexing errors found.");
            return;
        }

        LOGGER.info("{}-[CORE {}] Found {} flagged node documents, {} error node documents"
                        + " and {} content transform failures",
                Thread.currentThread().getId(), coreName, flaggedDocs.size(), errorNodeDocs.size(),
                transformFailedDocs.size());

        MetadataTracker metadataTracker = registry.getTrackerForCore(coreName, MetadataTracker.class);
        Semaphore metadataWriteLock = (metadataTracker != null) ? metadataTracker.getWriteLock() : null;

        try
        {
            if (metadataWriteLock != null)
            {
                metadataWriteLock.acquire();
            }

            for (TenantDbId docRef : flaggedDocs)
            {
                checkShutdown();
                processErrorNode(docRef, true);
            }

            for (TenantDbId docRef : errorNodeDocs)
            {
                checkShutdown();
                processErrorNode(docRef, false);
            }

            retryContentExtraction(transformFailedDocs);
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

    /**
     * @param docRef            the node to repair
     * @param hasNodeDocument   whether the index holds a node document for it. When it does
     *                          not, the error is recorded by an {@code ErrorNode} document,
     *                          which the indexer itself deletes on its next attempt — and
     *                          clearing the flag would then create an empty node document,
     *                          since an atomic update on a missing document creates one.
     */
    private void processErrorNode(TenantDbId docRef, boolean hasNodeDocument)
    {
        if (report.isPermanentlyFailed(docRef.dbId))
        {
            return;
        }

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
                if (hasNodeDocument)
                {
                    infoSrv.clearIndexingError(docRef.dbId, docRef.tenant);
                }
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
                    if (hasNodeDocument)
                    {
                        infoSrv.clearIndexingError(docRef.dbId, docRef.tenant);
                    }
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

    /**
     * Fetches again the text of the nodes whose content transformation failed, each one after
     * a delay that doubles with every failed attempt, and gives up after {@code maxRetries}.
     */
    private void retryContentExtraction(List<TenantDbId> transformFailedDocs)
    {
        Instant now = clock.instant();
        int processed = 0;
        for (TenantDbId docRef : transformFailedDocs)
        {
            if (processed >= CONTENT_RETRY_BATCH_SIZE)
            {
                return;
            }
            ContentRetry retry = contentRetries.get(docRef.dbId);
            if (retry != null && (retry.attempts() >= maxRetries || now.isBefore(retry.nextAttempt())))
            {
                continue;
            }
            checkShutdown();
            processed++;

            RepairResult result = contentStrategy.tryRepair(null, docRef);
            report.recordResult(result);
            if (result.success())
            {
                contentRetries.remove(docRef.dbId);
                LOGGER.info("[CORE {}] Repaired DBID={}: {}", coreName, docRef.dbId, result.message());
                continue;
            }

            int attempts = retry != null ? retry.attempts() + 1 : 1;
            Duration delay = CONTENT_RETRY_BASE_DELAY.multipliedBy(1L << Math.min(attempts - 1, 20));
            contentRetries.put(docRef.dbId, new ContentRetry(attempts, now.plus(delay)));
            if (attempts >= maxRetries)
            {
                LOGGER.warn("[CORE {}] DBID={} content extraction given up after {} attempts: {}",
                        coreName, docRef.dbId, attempts, result.message());
                report.markPermanentlyFailed(docRef.dbId);
            }
            else
            {
                LOGGER.debug("[CORE {}] DBID={} content extraction attempt {}/{} failed, next in {}: {}",
                        coreName, docRef.dbId, attempts, maxRetries, delay, result.message());
            }
        }
    }

    void setClock(Clock clock)
    {
        this.clock = clock;
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
