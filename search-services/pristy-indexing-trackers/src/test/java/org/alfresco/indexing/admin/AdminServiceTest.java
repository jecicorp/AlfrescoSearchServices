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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.alfresco.indexing.backup.BackupService;
import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.diagnostic.DiagnosticPhase;
import org.alfresco.indexing.diagnostic.ProgressListener;
import org.alfresco.indexing.server.solrj.JavaBitSetAdapter;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.InformationServerCollectionProvider;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.Before;
import org.junit.Test;

public class AdminServiceTest
{
    private final TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
    private final TrackerRegistry registry = mock(TrackerRegistry.class);
    private final MetadataTracker metadataTracker = mock(MetadataTracker.class);
    private final AclTracker aclTracker = mock(AclTracker.class);
    private final AdminService service = new AdminService(bootstrap, mock(SolrClient.class), mock(BackupService.class));

    @Before
    public void setUp()
    {
        when(bootstrap.getRegistry()).thenReturn(registry);
        when(registry.getTrackerForCore("alfresco", MetadataTracker.class)).thenReturn(metadataTracker);
        when(registry.getTrackerForCore("alfresco", AclTracker.class)).thenReturn(aclTracker);
    }

    @Test
    public void reportCoreHandsEachPhaseItsOwnListener() throws Exception
    {
        TrackerState state = new TrackerState();
        state.setLastIndexedTxId(10L);
        state.setLastIndexedChangeSetId(7L);
        when(metadataTracker.getTrackerState()).thenReturn(state);
        when(aclTracker.getTrackerState()).thenReturn(state);
        Map<DiagnosticPhase, ProgressListener> listeners = new EnumMap<>(DiagnosticPhase.class);
        for (DiagnosticPhase phase : DiagnosticPhase.values())
        {
            listeners.put(phase, mock(ProgressListener.class));
        }
        IndexHealthReport metadata = emptyReport();
        IndexHealthReport acl = emptyReport();
        when(metadataTracker.checkIndex(10L, null, null,
                listeners.get(DiagnosticPhase.METADATA_DB), listeners.get(DiagnosticPhase.METADATA_INDEX)))
                .thenReturn(metadata);
        when(aclTracker.checkIndex(7L, null, null,
                listeners.get(DiagnosticPhase.ACL_DB), listeners.get(DiagnosticPhase.ACL_INDEX)))
                .thenReturn(acl);

        Map<String, Object> section = service.reportCore("alfresco", null, null, listeners::get);

        assertEquals(Long.valueOf(0L), section.get("DB transaction count"));
        assertEquals(Long.valueOf(0L), section.get("DB acl transaction count"));
        assertFalse(section.toString(), section.containsKey("error"));
    }

    @Test
    public void aFailingCoreIsRecordedInItsSection() throws Exception
    {
        when(metadataTracker.checkIndex(any(), any(), any(), any(), any())).thenThrow(new IOException("Solr is down"));

        Map<String, Object> section = service.reportCore("alfresco", null, null, phase -> ProgressListener.NONE);

        assertEquals("Solr is down", section.get("error"));
    }

    @Test(expected = CancellationException.class)
    public void aCancellationRaisedByAListenerPropagates() throws Exception
    {
        when(metadataTracker.checkIndex(any(), any(), any(), any(), any())).thenThrow(new CancellationException());

        service.reportCore("alfresco", null, null, phase -> ProgressListener.NONE);
    }

    @Test
    public void theSynchronousReportStillCoversEveryCoreWithNoOpListeners() throws Exception
    {
        IndexHealthReport metadata = emptyReport();
        IndexHealthReport acl = emptyReport();
        when(registry.getCoreNames()).thenReturn(Set.of("alfresco"));
        when(metadataTracker.checkIndex(any(), any(), any(), same(ProgressListener.NONE), same(ProgressListener.NONE)))
                .thenReturn(metadata);
        when(aclTracker.checkIndex(any(), any(), any(), same(ProgressListener.NONE), same(ProgressListener.NONE)))
                .thenReturn(acl);

        Map<String, Object> result = service.report(null, null, null);

        assertEquals(Set.of("alfresco"), result.keySet());
        assertTrue(((Map<?, ?>) result.get("alfresco")).containsKey("DB transaction count"));
        assertTrue(((Map<?, ?>) result.get("alfresco")).containsKey("DB acl transaction count"));
    }

    private static IndexHealthReport emptyReport()
    {
        InformationServerCollectionProvider provider = mock(InformationServerCollectionProvider.class);
        when(provider.getOpenBitSetInstance()).thenAnswer(invocation -> new JavaBitSetAdapter());
        return new IndexHealthReport(provider);
    }
}
