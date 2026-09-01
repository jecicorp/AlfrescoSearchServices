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
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.Tracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.solr.tracker.TrackerStats;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RepairTrackerTest
{
    @Mock private InformationServer infoSrv;
    @Mock private SOLRAPIClient client;
    @Mock private TrackerRegistry registry;
    @Mock private MetadataTracker metadataTracker;
    @Mock private RepairStrategy strategy;
    @Mock private TrackerStats trackerStats;

    private RepairTracker tracker;
    private Properties props;
    private Semaphore writeLock = new Semaphore(1, true);

    @Before
    public void setUp()
    {
        props = new Properties();
        props.setProperty("alfresco.stores", "workspace://SpacesStore");
        props.setProperty("alfresco.batch.count", "100");
        props.setProperty("alfresco.repair.tracker.cron", "0/60 * * * * ?");

        when(infoSrv.getTrackerStats()).thenReturn(trackerStats);
        when(registry.getTrackerForCore(anyString(), eq(MetadataTracker.class))).thenReturn(metadataTracker);
        when(metadataTracker.getWriteLock()).thenReturn(writeLock);

        tracker = new RepairTracker(props, client, "alfresco", infoSrv,
                List.of(strategy), registry, 10);
    }

    @Test
    public void type_isREPAIR()
    {
        assertEquals(Tracker.Type.REPAIR, tracker.getType());
    }

    @Test
    public void doTrack_noErrors_doesNothing() throws Throwable
    {
        when(infoSrv.getDocsWithIndexingError()).thenReturn(Collections.emptyList());
        tracker.doTrack("test-1");
        verify(strategy, never()).tryRepair(any(), any());
    }

    @Test
    public void doTrack_strategyReturnsNull_recordedAsUnknown() throws Throwable
    {
        TenantDbId docRef = new TenantDbId();
        docRef.dbId = 42L;
        docRef.tenant = "";
        when(infoSrv.getDocsWithIndexingError()).thenReturn(List.of(docRef));
        when(strategy.tryRepair(any(), any())).thenReturn(null);

        tracker.doTrack("test-2");

        verify(infoSrv, never()).clearIndexingError(anyLong(), anyString());
        RepairReport report = tracker.getReport();
        assertEquals(1, report.getPendingErrors().size());
        assertEquals("UNKNOWN", report.getPendingErrors().get(0).category());
    }

    @Test
    public void doTrack_strategySucceeds_clearsError() throws Throwable
    {
        TenantDbId docRef = new TenantDbId();
        docRef.dbId = 42L;
        docRef.tenant = "";
        when(infoSrv.getDocsWithIndexingError()).thenReturn(List.of(docRef));
        when(strategy.tryRepair(any(), any())).thenReturn(
                new RepairResult(42L, true, "UNRESOLVED_MODEL", "Fixed"));

        tracker.doTrack("test-3");

        verify(infoSrv).clearIndexingError(42L, "");
        assertEquals(1, tracker.getReport().getRecentRepairs().size());
    }

    @Test
    public void doTrack_strategyFails_maxRetries_marksAsPermanent() throws Throwable
    {
        tracker = new RepairTracker(props, client, "alfresco", infoSrv,
                List.of(strategy), registry, 2);

        TenantDbId docRef = new TenantDbId();
        docRef.dbId = 42L;
        docRef.tenant = "";
        when(infoSrv.getDocsWithIndexingError()).thenReturn(List.of(docRef));
        when(strategy.tryRepair(any(), any())).thenReturn(
                new RepairResult(42L, false, "UNRESOLVED_MODEL", "Model still missing"));

        tracker.doTrack("test-4a");
        assertEquals(1, tracker.getReport().getPendingErrors().size());

        tracker.doTrack("test-4b");
        verify(infoSrv).clearIndexingError(42L, "");
        assertEquals(0, tracker.getReport().getPendingErrors().size());
    }
}
