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

import static java.util.stream.Collectors.joining;
import static java.util.Collections.emptyList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;

import com.google.common.collect.Lists;
import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.Transaction;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This tracks Cascading Updates
 * @author Joel Bernstein
 */
public class CascadeTracker extends ActivatableTracker
{

    protected final static Logger LOGGER = LoggerFactory.getLogger(CascadeTracker.class);

    private static final int DEFAULT_CASCADE_TRACKER_MAX_PARALLELISM = 32;
    private static final int DEFAULT_CASCADE_NODE_BATCH_SIZE = 10;
    private static final int DEFAULT_CASCADE_COMMIT_INTERVAL = 30;

    // Share run and write locks across all CascadeTracker threads
    private static Map<String, Semaphore> RUN_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private static Map<String, Semaphore> WRITE_LOCK_BY_CORE = new ConcurrentHashMap<>();
    private int cascadeBatchSize;
    private ForkJoinPool forkJoinPool;
    private int cascadeTrackerParallelism;
    private int cascadeCommitInterval;

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


    public CascadeTracker(Properties p, SOLRAPIClient client, String coreName,
                           InformationServer informationServer)
    {
        super(p, client, coreName, informationServer, Tracker.Type.CASCADE);

        cascadeTrackerParallelism = Integer.parseInt(p.getProperty("alfresco.cascade.tracker.maxParallelism",
                String.valueOf(DEFAULT_CASCADE_TRACKER_MAX_PARALLELISM)));

        cascadeBatchSize = Integer.parseInt(p.getProperty("alfresco.cascade.tracker.nodeBatchSize",
                String.valueOf(DEFAULT_CASCADE_NODE_BATCH_SIZE)));

        cascadeCommitInterval = Integer.parseInt(p.getProperty("alfresco.cascade.tracker.commitInterval",
                String.valueOf(DEFAULT_CASCADE_COMMIT_INTERVAL)));

        forkJoinPool = new ForkJoinPool(cascadeTrackerParallelism);
        RUN_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
        WRITE_LOCK_BY_CORE.put(coreName, new Semaphore(1, true));
    }

    CascadeTracker()
    {
       super(Tracker.Type.CASCADE);
    }

    @Override
    protected void doTrack(String iterationId) throws IOException, JSONException
    {
        // MetadataTracker must wait until ModelTracker has run
        ModelTracker modelTracker = this.infoSrv.getTrackerRegistry().getModelTracker();
        if (modelTracker != null && modelTracker.hasModels())
        {
            trackRepository(iterationId);
        }
    }

    public void maintenance()
    {
    }

    public boolean hasMaintenance() {
        return false;
    }

    private void trackRepository(String iterationId) throws IOException, JSONException
    {
        checkShutdown();
        processCascades(iterationId);
    }

    private void updateTransactionsAfterWorker(List<Transaction> txsIndexed)
            throws IOException
    {
        for (Transaction tx : txsIndexed)
        {
            super.infoSrv.updateTransaction(tx);
        }
    }

    class CascadeIndexWorker extends AbstractWorker
    {
        InformationServer infoServer;
        List<NodeMetaData> nodes;

        CascadeIndexWorker(List<NodeMetaData> nodes, InformationServer infoServer)
        {
            this.infoServer = infoServer;
            this.nodes = nodes;
        }

        @Override
        protected void doWork() throws IOException, AuthenticationException, JSONException
        {
            this.infoServer.cascadeNodes(nodes, true);
        }

        @Override
        protected void onFail(Throwable failCausedBy)
        {
            setRollback(true, failCausedBy);
        }
    }

    public void invalidateState()
    {
        super.invalidateState();
        infoSrv.setCleanCascadeTxnFloor(-1);
    }

    private void processCascades(String iterationId) throws IOException
    {
        int num = 50;
        long totalUpdatedDocs = 0;
        Set<Long> processedTxIds = new HashSet<>();
        int batchesSinceCommit = 0;

        while (true)
        {
            List<Transaction> txBatch;
            try
            {
                getWriteLock().acquire();

                txBatch = infoSrv.getCascades(num);

                // Filter out transactions already processed in this iteration
                // (Solr searcher may not yet reflect the flag=0 updates)
                txBatch.removeIf(tx -> processedTxIds.contains(tx.getId()));

                if (txBatch.isEmpty())
                {
                    if (batchesSinceCommit > 0)
                    {
                        // Final hard commit with waitSearcher=true
                        infoSrv.commit(true);
                        LOGGER.debug("{}-[CORE {}] Final commit after {} batches",
                                Thread.currentThread().getId(), coreName, batchesSinceCommit);
                    }
                    break;
                }

                LOGGER.info("{}-[CORE {}] Processing {} transactions (cascade), from {} to {}",
                        Thread.currentThread().getId(),
                        coreName,
                        txBatch.size(),
                        txBatch.get(0),
                        txBatch.get(txBatch.size() - 1));

                ArrayList<Long> txIds = new ArrayList<>();
                for (Transaction tx : txBatch)
                {
                    txIds.add(tx.getId());
                }

                List<NodeMetaData> nodeMetaDatas = infoSrv.getCascadeNodes(txIds);
                int processedCascades = 0;

                if (!nodeMetaDatas.isEmpty())
                {
                    List<List<NodeMetaData>> nodeBatches = Lists.partition(nodeMetaDatas, cascadeBatchSize);

                    processedCascades = forkJoinPool.submit(() ->
                            nodeBatches.parallelStream().map(batch -> {

                                CascadeIndexWorker worker = new CascadeIndexWorker(batch, infoSrv);
                                worker.run();

                                if (LOGGER.isTraceEnabled())
                                {
                                    Collection<NodeMetaData> safeBatch = batch != null ? batch : emptyList();
                                    String nodes = safeBatch.stream()
                                            .map(NodeMetaData::getId)
                                            .map(Object::toString)
                                            .collect(joining(","));
                                    LOGGER.trace("[{} / {} / {} / {}] Worker has been created for nodes {}",
                                            coreName, trackerId, iterationId, worker.hashCode(), nodes);
                                }
                                return batch.size();
                            }).reduce(0, Integer::sum)
                    ).get();
                }

                // Update the transaction records (set cascade flag to 0) — no commit yet.
                updateTransactionsAfterWorker(txBatch);
                processedTxIds.addAll(txIds);
                totalUpdatedDocs += processedCascades;
                batchesSinceCommit++;

                // Periodic hard commit to flush to disk and refresh the searcher
                if (batchesSinceCommit >= cascadeCommitInterval)
                {
                    infoSrv.commit(true);
                    LOGGER.info("{}-[CORE {}] Periodic commit after {} batches ({} txns processed so far)",
                            Thread.currentThread().getId(), coreName, batchesSinceCommit, processedTxIds.size());
                    processedTxIds.clear();
                    batchesSinceCommit = 0;
                }
            }
            catch (AuthenticationException | JSONException e)
            {
                throw new IOException(e);
            }
            catch (InterruptedException e)
            {
                throw new IOException(e);
            }
            catch (ExecutionException e)
            {
                LOGGER.error("{}-[CORE {}] Cascade worker execution failed", Thread.currentThread().getId(), coreName, e);
            }
            finally
            {
                getWriteLock().release();
            }
        }

        LOGGER.info("{}-[CORE {}] Cascade processing complete — updated {} docs",
                Thread.currentThread().getId(), coreName, totalUpdatedDocs);
    }
}
