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

package org.alfresco.indexing.progress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.solrj.SolrJInformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.CascadeTracker;
import org.alfresco.indexing.tracker.ContentTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.repair.RepairReport;
import org.alfresco.indexing.tracker.repair.RepairResult;
import org.alfresco.indexing.tracker.repair.RepairTracker;
import org.alfresco.solr.TrackerState;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.tracker.TrackerStats;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins what each tracker contributes to a reading.
 */
public class TrackerRegistryProgressSourceTest
{
    private TrackerBootstrap bootstrap;
    private TrackerRegistry registry;
    private SolrJInformationServer informationServer;
    private TrackerRegistryProgressSource source;

    @Before
    public void setUp()
    {
        bootstrap = mock(TrackerBootstrap.class);
        registry = mock(TrackerRegistry.class);
        informationServer = mock(SolrJInformationServer.class);
        when(bootstrap.getRegistry()).thenReturn(registry);
        when(bootstrap.getInformationServer("alfresco")).thenReturn(informationServer);
        when(registry.getCoreNames()).thenReturn(Set.of("alfresco"));
        TrackerStats stats = mock(TrackerStats.class);
        when(stats.getMeanDocsPerTx()).thenReturn(1.5d);
        when(stats.getMeanAclsPerChangeSet()).thenReturn(2.0d);
        when(informationServer.getTrackerStats()).thenReturn(stats);
        contentCounts(900L, 100L);
        source = new TrackerRegistryProgressSource(bootstrap);
    }

    @Test
    public void readsTheMetadataBacklogFromTheTrackerCursor()
    {
        MetadataTracker tracker = mock(MetadataTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.getTrackerState()).thenReturn(state(4_000L, 4_250L, 0L, 0L));
        when(registry.getTrackerForCore("alfresco", MetadataTracker.class)).thenReturn(tracker);

        TrackerReading reading = readingFor("metadata");

        assertTrue(reading.active());
        assertEquals(Long.valueOf(4_000L), reading.done());
        assertEquals(Long.valueOf(250L), reading.remaining());
        assertEquals(Double.valueOf(1.5d), reading.docsPerUnit());
    }

    @Test
    public void readsTheAclBacklogFromTheChangeSetCursor()
    {
        AclTracker tracker = mock(AclTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.getTrackerState()).thenReturn(state(0L, 0L, 80L, 100L));
        when(registry.getTrackerForCore("alfresco", AclTracker.class)).thenReturn(tracker);

        TrackerReading reading = readingFor("acl");

        assertEquals(Long.valueOf(80L), reading.done());
        assertEquals(Long.valueOf(20L), reading.remaining());
        assertEquals(Double.valueOf(2.0d), reading.docsPerUnit());
    }

    @Test
    public void readsTheContentBacklogFromTheIndexItself()
    {
        ContentTracker tracker = mock(ContentTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(registry.getTrackerForCore("alfresco", ContentTracker.class)).thenReturn(tracker);

        TrackerReading reading = readingFor("content");

        assertEquals("content in sync is the cursor", Long.valueOf(900L), reading.done());
        assertEquals(Long.valueOf(100L), reading.remaining());
        assertNull("the content backlog is already counted in nodes", reading.docsPerUnit());
    }

    @Test
    public void countsTheTransactionsStillWaitingToCascade() throws Exception
    {
        CascadeTracker tracker = mock(CascadeTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(registry.getTrackerForCore("alfresco", CascadeTracker.class)).thenReturn(tracker);
        when(informationServer.getPendingCascadeCount()).thenReturn(42L);

        TrackerReading reading = readingFor("cascade");

        assertTrue(reading.active());
        assertEquals(Long.valueOf(42L), reading.remaining());
        assertNull("a cascade backlog has no cursor to measure a speed against",
                reading.done());
    }

    @Test
    public void countsTheNodesStillWaitingToBeRepaired()
    {
        RepairTracker tracker = mock(RepairTracker.class);
        RepairReport report = new RepairReport();
        report.startCycle();
        report.recordResult(new RepairResult(7L, false, "unresolved-model", "not loaded"));
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.getReport()).thenReturn(report);
        when(registry.getTrackerForCore("alfresco", RepairTracker.class)).thenReturn(tracker);

        TrackerReading reading = readingFor("repair");

        assertEquals(Long.valueOf(1L), reading.remaining());
        assertNull(reading.done());
    }

    @Test
    public void reportsACascadeTrackerWhoseCountIsUnavailableAsRunningOnly() throws Exception
    {
        CascadeTracker tracker = mock(CascadeTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(registry.getTrackerForCore("alfresco", CascadeTracker.class)).thenReturn(tracker);
        when(informationServer.getPendingCascadeCount())
                .thenThrow(new IllegalStateException("core not ready"));

        TrackerReading reading = readingFor("cascade");

        assertTrue(reading.active());
        assertNull(reading.remaining());
    }

    @Test
    public void reportsATrackerWhoseStateIsNotReadyYetWithoutCounters()
    {
        MetadataTracker tracker = mock(MetadataTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(tracker.getTrackerState()).thenReturn(null);
        when(registry.getTrackerForCore("alfresco", MetadataTracker.class)).thenReturn(tracker);

        TrackerReading reading = readingFor("metadata");

        assertNull("a tracker that has not polled yet has no cursor to report",
                reading.done());
        assertNull(reading.remaining());
    }

    @Test
    public void reportsNothingForATrackerThatIsNotRegistered()
    {
        assertTrue(source.read().stream().noneMatch(r -> "metadata".equals(r.tracker())));
    }

    @Test
    public void keepsReadingTheOtherCoresWhenOneFails()
    {
        when(registry.getCoreNames()).thenReturn(Set.of("alfresco", "archive"));
        when(bootstrap.getInformationServer("archive"))
                .thenThrow(new IllegalStateException("core not started"));
        ContentTracker tracker = mock(ContentTracker.class);
        when(tracker.isEnabled()).thenReturn(true);
        when(registry.getTrackerForCore("alfresco", ContentTracker.class)).thenReturn(tracker);

        List<TrackerReading> readings = source.read();

        assertTrue(readings.stream().anyMatch(r -> "alfresco".equals(r.core())));
        assertTrue(readings.stream().noneMatch(r -> "archive".equals(r.core())));
    }

    private void contentCounts(long inSync, long outdated)
    {
        doAnswer(invocation -> {
            Map<String, Object> report = invocation.getArgument(0);
            report.put("Node count whose content is in sync", inSync);
            report.put("Node count whose content needs to be updated", outdated);
            return null;
        }).when(informationServer).addContentOutdatedAndUpdatedCounts(any());
    }

    private TrackerReading readingFor(String tracker)
    {
        Optional<TrackerReading> reading = source.read().stream()
                .filter(r -> tracker.equals(r.tracker()))
                .findFirst();
        assertTrue("no reading for tracker " + tracker, reading.isPresent());
        return reading.get();
    }

    private static TrackerState state(long lastTx, long lastTxOnServer, long lastAcl,
            long lastAclOnServer)
    {
        TrackerState state = new TrackerState();
        state.setLastIndexedTxId(lastTx);
        state.setLastTxIdOnServer(lastTxOnServer);
        state.setLastIndexedChangeSetId(lastAcl);
        state.setLastChangeSetIdOnServer(lastAclOnServer);
        return state;
    }
}
