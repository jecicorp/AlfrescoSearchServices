/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
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

package org.alfresco.indexing.tracker;

import com.google.common.collect.Lists;
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.SOLRAPIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * This tracker queries for docs with unclean content, and then updates them.
 * Similar to org.alfresco.repo.search.impl.lucene.ADMLuceneIndexerImpl
 *
 * @author Ahmed Owian
 */
public class ContentTracker extends ActivatableTracker
{
    protected final static Logger LOGGER = LoggerFactory.getLogger(ContentTracker.class);

    // Keep this value to 1/4 of all the other pools, as ContentTracker Threads are heavier
    private static final int DEFAULT_CONTENT_TRACKER_MAX_PARALLELISM = 8;
    private static final int DEFAULT_CONTENT_UPDATE_BATCH_SIZE = 2000;
    private static final int DEFAULT_MAX_DOCUMENTS_PER_CYCLE = 2000;

    private int contentTrackerParallelism;
    private int contentUpdateBatchSize;
    private int maxDocumentsPerCycle;

    // Share run and write locks across all ContentTracker threads
    private static final Map<String, Semaphore> RUN_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private static final Map<String, Semaphore> WRITE_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private ForkJoinPool forkJoinPool;

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

    public ContentTracker(Properties p, SOLRAPIClient client, String coreName, InformationServer informationServer)
    {
        super(p, client, coreName, informationServer, Tracker.Type.CONTENT);

        contentUpdateBatchSize = Integer.parseInt(p.getProperty("alfresco.contentUpdateBatchSize",
                String.valueOf(DEFAULT_CONTENT_UPDATE_BATCH_SIZE)));

        contentTrackerParallelism = Integer.parseInt(p.getProperty("alfresco.content.tracker.maxParallelism",
                String.valueOf(DEFAULT_CONTENT_TRACKER_MAX_PARALLELISM)));

        maxDocumentsPerCycle = Integer.parseInt(p.getProperty("alfresco.content.tracker.maxDocumentsPerCycle",
                String.valueOf(DEFAULT_MAX_DOCUMENTS_PER_CYCLE)));

        validatePositive("alfresco.contentUpdateBatchSize", contentUpdateBatchSize);
        validatePositive("alfresco.content.tracker.maxParallelism", contentTrackerParallelism);
        validatePositive("alfresco.content.tracker.maxDocumentsPerCycle", maxDocumentsPerCycle);

        forkJoinPool = new ForkJoinPool(contentTrackerParallelism);

        RUN_LOCK_BY_CORE.computeIfAbsent(coreName, ignored -> new Semaphore(1, true));
        WRITE_LOCK_BY_CORE.computeIfAbsent(coreName, ignored -> new Semaphore(1, true));
    }

    ContentTracker()
    {
       super(Tracker.Type.CONTENT);
    }

    @Override
    protected void doTrack(String iterationId) throws Exception
    {
        try
        {
            long startElapsed = System.nanoTime();

            checkShutdown();
            List<TenantDbId> docs;
            long totalProcessedDocuments = 0L;
            getWriteLock().acquire();
            try
            {
                docs = this.infoSrv.getDocsWithUncleanContent(maxDocumentsPerCycle);
                if (docs == null)
                {
                    docs = Collections.emptyList();
                }
                if (docs.size() > maxDocumentsPerCycle)
                {
                    docs = docs.subList(0, maxDocumentsPerCycle);
                }

                List<List<TenantDbId>> docBatches = Lists.partition(docs, contentUpdateBatchSize);
                for (List<TenantDbId> batch : docBatches)
                {
                    int processedDocuments = processBatch(batch);
                    totalProcessedDocuments += processedDocuments;

                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedContentTime(processedDocuments, endElapsed - startElapsed);
                    startElapsed = endElapsed;
                    checkShutdown();
                }
            }
            finally
            {
                getWriteLock().release();
            }

            if (docs.isEmpty())
            {
                LOGGER.trace("No unclean document has been detected in the current ContentTracker cycle.");
                LOGGER.debug("{}-[CORE {}] Total number of docs with content updated: 0",
                        Thread.currentThread().getId(), coreName);
            }
            else
            {
                LOGGER.info("{}-[CORE {}] Total number of docs with content updated: {}",
                        Thread.currentThread().getId(), coreName, totalProcessedDocuments);
            }
        }
        catch(Exception e)
        {
            throw new IOException(e);
        }
    }

    private int processBatch(List<TenantDbId> batch) throws InterruptedException, ExecutionException
    {
        List<Callable<Integer>> workers = batch.stream()
                .map(doc -> (Callable<Integer>) () -> {
                    ContentIndexWorkerRunnable worker = new ContentIndexWorkerRunnable(doc, infoSrv);
                    worker.run();
                    return worker.wasSuccessful() ? 1 : 0;
                })
                .toList();

        int processedDocuments = 0;
        for (Future<Integer> result : forkJoinPool.invokeAll(workers))
        {
            processedDocuments += result.get();
        }
        return processedDocuments;
    }

    private static void validatePositive(String propertyName, int value)
    {
        if (value <= 0)
        {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        if (forkJoinPool != null)
        {
            forkJoinPool.shutdown();
        }
    }

    public boolean hasMaintenance()
    {
        return false;
    }

    public void maintenance()
    {
        // Nothing to be done here
    }

    public void invalidateState()
    {
        super.invalidateState();
        this.infoSrv.setCleanContentTxnFloor(-1);
    }

    class ContentIndexWorkerRunnable extends AbstractWorker
    {
        InformationServer infoServer;
        TenantDbId docRef;
        private boolean successful;

        ContentIndexWorkerRunnable(TenantDbId doc, InformationServer infoServer)
        {
            this.docRef = doc;
            this.infoServer = infoServer;
        }

        @Override
        protected void doWork() throws Exception
        {
            checkShutdown();

            infoServer.updateContent(docRef);
            successful = true;
        }

        @Override
        protected void onFail(Throwable failCausedBy)
        {
            // This will be redone in future tracking operations
            LOGGER.warn("Content tracker failed due to {}", failCausedBy.getMessage(), failCausedBy);
        }

        boolean wasSuccessful()
        {
            return successful;
        }
    }
}
