/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2022 Alfresco Software Limited
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

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.alfresco.solr.client.TenantDbId;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.tracker.TrackerStats;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ContentTrackerTest
{
    private ContentTracker contentTracker;

    @Mock
    private SOLRAPIClient repositoryClient;
    private String coreName = "theCoreName";
    @Mock
    private InformationServer srv;
    @Spy
    private Properties props;
    @Mock
    private TrackerStats trackerStats;

    private int UPDATE_BATCH = 2;

    @Before
    public void setUp() throws Exception
    {
        doReturn("workspace://SpacesStore").when(props).getProperty(eq("alfresco.stores"), anyString());
        doReturn("" + UPDATE_BATCH).when(props).getProperty(eq("alfresco.contentUpdateBatchSize"), anyString());
        when(srv.getTrackerStats()).thenReturn(trackerStats);
        when(srv.getTrackerInitialState()).thenAnswer(invocation -> new TrackerState());
        this.contentTracker = new ContentTracker(props, repositoryClient, coreName, srv);

    }

    @Test
    @Ignore("Superseded by AlfrescoSolrTrackerTest")

    public void doTrackWithNoContentDoesNothing() throws Exception
    {
        this.contentTracker.doTrack("anIterationId");
        verify(srv, never()).updateContent(any());
        verify(srv, never()).commit();
    }

    @Test
    @Ignore("Superseded by AlfrescoSolrTrackerTest")
    public void doTrackWithContentUpdatesContent() throws Exception
    {
        List<TenantDbId> docs1 = new ArrayList<>();
        List<TenantDbId> docs2 = new ArrayList<>();
        List<TenantDbId> emptyList = new ArrayList<>();
        // Adds one more than the UPDATE_BATCH
        for (int i = 0; i <= UPDATE_BATCH; i++)
        {
            TenantDbId doc = new TenantDbId();
            doc.dbId = 1l;
            doc.tenant = "1";
            docs1.add(doc);
        }
        TenantDbId thirdDoc = docs1.get(UPDATE_BATCH);
        thirdDoc.dbId = 3l;
        thirdDoc.tenant = "3";

        // Adds UPDATE_BATCH
        for (long i = 0; i < UPDATE_BATCH; i++)
        {
            TenantDbId doc = new TenantDbId();
            doc.dbId = 2l;
            doc.tenant = "2";
            docs2.add(doc);
        }
        when(this.srv.getDocsWithUncleanContent())
                .thenReturn(docs1)
                .thenReturn(docs2)
            .thenReturn(emptyList);
        this.contentTracker.doTrack("anIterationId");

        InOrder order = inOrder(srv);
        order.verify(srv).getDocsWithUncleanContent();

        /*
         * I had to make each bunch of calls have different parameters to prevent Mockito from incorrectly failing
         * because it was finding 5 calls instead of finding the first two calls, then the commit, then the rest...
         * It seems that Mockito has a bug with verification in order.
         * See https://code.google.com/p/mockito/issues/detail?id=296
         */

        // From docs1
        TenantDbId docRef = new TenantDbId();
        docRef.dbId = 1L;
        docRef.tenant = "1";

        order.verify(srv, times(UPDATE_BATCH)).updateContent(docRef);
        order.verify(srv).commit();

        // The one extra doc should be processed and then committed
        order.verify(srv).updateContent(thirdDoc);
        order.verify(srv).commit();

        order.verify(srv).getDocsWithUncleanContent();

        // From docs2
        docRef = new TenantDbId();
        docRef.dbId = 2L;
        docRef.tenant = "2";
        order.verify(srv, times(UPDATE_BATCH)).updateContent(docRef);
        order.verify(srv).commit();

        order.verify(srv).getDocsWithUncleanContent();
    }
    @Test
    public void typeCheck()
    {
        Assert.assertEquals(contentTracker.getType(), Tracker.Type.CONTENT);
    }

    @Test
    public void cycleProcessesNoMoreThanConfiguredMaximum() throws Exception
    {
        ContentTracker tracker = trackerWithLimits(2, 1, 3);
        when(srv.getDocsWithUncleanContent(3)).thenReturn(documents(5));

        tracker.doTrack("bounded-cycle");

        verify(srv).getDocsWithUncleanContent(3);
        verify(srv, times(3)).updateContent(any(TenantDbId.class));
    }

    @Test
    public void repeatedBoundedCyclesEventuallyProcessCompleteBacklog() throws Exception
    {
        ContentTracker tracker = trackerWithLimits(2, 1, 3);
        List<TenantDbId> backlog = new CopyOnWriteArrayList<>(documents(8));
        stubBacklog(backlog);

        tracker.doTrack("cycle-1");
        Assert.assertEquals(5, backlog.size());

        tracker.doTrack("cycle-2");
        Assert.assertEquals(2, backlog.size());

        tracker.doTrack("cycle-3");
        Assert.assertTrue(backlog.isEmpty());
        verify(srv, times(3)).getDocsWithUncleanContent(3);
        verify(srv, times(8)).updateContent(any(TenantDbId.class));
    }

    @Test
    public void smallerBatchSizeCreatesMultipleInternalBatchesWithoutExceedingCycleMaximum() throws Exception
    {
        ContentTracker tracker = trackerWithLimits(2, 1, 5);
        when(srv.getDocsWithUncleanContent(5)).thenReturn(documents(5));

        tracker.doTrack("multiple-batches");

        verify(srv, times(5)).updateContent(any(TenantDbId.class));
        verify(trackerStats, times(3)).addElapsedContentTime(anyInt(), anyLong());
    }

    @Test
    public void failedContentUpdatesAreNotCountedAsProcessed() throws Exception
    {
        ContentTracker tracker = trackerWithLimits(2, 1, 2);
        when(srv.getDocsWithUncleanContent(2)).thenReturn(documents(2));
        doThrow(new IOException("content failed"))
                .doNothing()
                .when(srv).updateContent(any(TenantDbId.class));

        tracker.doTrack("partial-failure");

        verify(srv, times(2)).updateContent(any(TenantDbId.class));
        verify(trackerStats).addElapsedContentTime(eq(1), anyLong());
    }

    @Test
    public void maxParallelismOneProcessesSequentially() throws Exception
    {
        ContentTracker tracker = trackerWithLimits(6, 1, 6);
        when(srv.getDocsWithUncleanContent(6)).thenReturn(documents(6));
        AtomicInteger activeWorkers = new AtomicInteger();
        AtomicInteger maximumWorkers = new AtomicInteger();
        doAnswer(invocation -> {
            int active = activeWorkers.incrementAndGet();
            maximumWorkers.accumulateAndGet(active, Math::max);
            try
            {
                Thread.sleep(10);
            }
            finally
            {
                activeWorkers.decrementAndGet();
            }
            return null;
        }).when(srv).updateContent(any(TenantDbId.class));

        tracker.doTrack("sequential");

        Assert.assertEquals(1, maximumWorkers.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLegacyParallelismFailsFast()
    {
        trackerWithLimits(10, 0, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLegacyBatchSizeFailsFast()
    {
        trackerWithLimits(0, 1, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLegacyMaxDocumentsPerCycleFailsFast()
    {
        trackerWithLimits(10, 1, -1);
    }

    @Test
    public void trackersForSameCoreKeepTheSameOverlapLocks() throws Exception
    {
        ContentTracker first = trackerWithLimits(10, 1, 10);
        java.util.concurrent.Semaphore originalRunLock = first.getRunLock();
        java.util.concurrent.Semaphore originalWriteLock = first.getWriteLock();
        originalRunLock.acquire();
        originalWriteLock.acquire();
        try
        {
            ContentTracker second = trackerWithLimits(10, 1, 10);

            Assert.assertSame(originalRunLock, second.getRunLock());
            Assert.assertSame(originalWriteLock, second.getWriteLock());
        }
        finally
        {
            originalRunLock.release();
            originalWriteLock.release();
        }
    }

    @Test
    public void trackSkipsOverlappingCycleForSameCore() throws Exception
    {
        ContentTracker tracker = trackerWithLimits("overlapCore", 10, 1, 10);
        when(srv.getDocsWithUncleanContent(10)).thenReturn(documents(1));
        CountDownLatch contentUpdateStarted = new CountDownLatch(1);
        CountDownLatch releaseContentUpdate = new CountDownLatch(1);
        doAnswer(invocation -> {
            contentUpdateStarted.countDown();
            Assert.assertTrue(releaseContentUpdate.await(5, TimeUnit.SECONDS));
            return null;
        }).when(srv).updateContent(any(TenantDbId.class));

        Thread firstCycle = new Thread(tracker::track);
        firstCycle.start();
        Assert.assertTrue(contentUpdateStarted.await(5, TimeUnit.SECONDS));

        Thread overlappingCycle = new Thread(tracker::track);
        overlappingCycle.start();
        overlappingCycle.join(5000);

        releaseContentUpdate.countDown();
        firstCycle.join(5000);

        verify(srv, times(1)).getDocsWithUncleanContent(10);
        verify(srv, times(1)).updateContent(any(TenantDbId.class));
    }

    private ContentTracker trackerWithLimits(int batchSize, int maxParallelism, int maxDocumentsPerCycle)
    {
        return trackerWithLimits(coreName, batchSize, maxParallelism, maxDocumentsPerCycle);
    }

    private ContentTracker trackerWithLimits(String trackerCoreName, int batchSize, int maxParallelism,
                                             int maxDocumentsPerCycle)
    {
        Properties limits = new Properties();
        limits.setProperty("alfresco.stores", "workspace://SpacesStore");
        limits.setProperty("alfresco.contentUpdateBatchSize", String.valueOf(batchSize));
        limits.setProperty("alfresco.content.tracker.maxParallelism", String.valueOf(maxParallelism));
        limits.setProperty("alfresco.content.tracker.maxDocumentsPerCycle", String.valueOf(maxDocumentsPerCycle));
        return new ContentTracker(limits, repositoryClient, trackerCoreName, srv);
    }

    private void stubBacklog(List<TenantDbId> backlog) throws Exception
    {
        when(srv.getDocsWithUncleanContent(anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(0);
            return new ArrayList<>(backlog.subList(0, Math.min(limit, backlog.size())));
        });
        doAnswer(invocation -> {
            TenantDbId processed = invocation.getArgument(0);
            backlog.removeIf(candidate -> candidate.dbId == processed.dbId);
            return null;
        }).when(srv).updateContent(any(TenantDbId.class));
    }

    private List<TenantDbId> documents(int count)
    {
        if (count == 0)
        {
            return Collections.emptyList();
        }
        List<TenantDbId> documents = new ArrayList<>();
        for (int i = 1; i <= count; i++)
        {
            TenantDbId document = new TenantDbId();
            document.dbId = i;
            document.tenant = "";
            documents.add(document);
        }
        return documents;
    }
}
