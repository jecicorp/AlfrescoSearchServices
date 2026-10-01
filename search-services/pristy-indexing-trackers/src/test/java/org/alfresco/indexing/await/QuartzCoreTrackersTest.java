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

package org.alfresco.indexing.await;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Properties;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.CommitListener;
import org.alfresco.indexing.tracker.CommitTracker;
import org.alfresco.indexing.tracker.ContentTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.indexing.tracker.TrackerScheduler;
import org.junit.Test;

public class QuartzCoreTrackersTest
{
    private static final String CORE = "alfresco";

    private final TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
    private final TrackerScheduler scheduler = mock(TrackerScheduler.class);
    private final TrackerRegistry registry = new TrackerRegistry();
    private final QuartzCoreTrackers trackers = new QuartzCoreTrackers(bootstrap);
    private final Runnable started = () -> { };
    private final Runnable ended = () -> { };
    private final CommitListener listener = core -> { };

    @Test
    public void beforeTheTrackersStartNothingCanBeHookedOrTriggered()
    {
        assertFalse(trackers.hook(CORE, started, ended, listener));
        assertFalse(trackers.triggerMetadata(CORE, 1000L));
        assertFalse(trackers.triggerCommit(CORE));
    }

    @Test
    public void hookingRegistersTheRunCallbacksAndTheCommitListener()
    {
        CommitTracker commitTracker = commitTracker();
        registry.register(CORE, commitTracker);
        running();
        when(scheduler.onTriggeredRun("MetadataTracker", CORE, started, ended)).thenReturn(true);

        assertTrue(trackers.hook(CORE, started, ended, listener));

        assertEquals(List.of(listener), commitTracker.getCommitListeners());
    }

    @Test
    public void aCoreWithoutCommitTrackerCannotBeHooked()
    {
        running();

        assertFalse(trackers.hook(CORE, started, ended, listener));

        verify(scheduler, never()).onTriggeredRun(anyString(), anyString(), any(Runnable.class), any(Runnable.class));
    }

    @Test
    public void aRefusedListenerLeavesTheCommitTrackerUntouched()
    {
        CommitTracker commitTracker = commitTracker();
        registry.register(CORE, commitTracker);
        running();
        when(scheduler.onTriggeredRun("MetadataTracker", CORE, started, ended)).thenReturn(false);

        assertFalse(trackers.hook(CORE, started, ended, listener));

        assertEquals(List.of(), commitTracker.getCommitListeners());
    }

    @Test
    public void theMetadataJobWaitsTheLagAndTheCommitJobRunsAtOnce()
    {
        running();
        when(scheduler.triggerOnce("MetadataTracker", CORE, 1000L)).thenReturn(true);
        when(scheduler.triggerOnce("CommitTracker", CORE, 0L)).thenReturn(true);

        assertTrue(trackers.triggerMetadata(CORE, 1000L));
        assertTrue(trackers.triggerCommit(CORE));
    }

    private void running()
    {
        when(bootstrap.getScheduler()).thenReturn(scheduler);
        when(bootstrap.getRegistry()).thenReturn(registry);
    }

    private static CommitTracker commitTracker()
    {
        return new CommitTracker(new Properties(), null, CORE, mock(InformationServer.class),
                List.of(mock(MetadataTracker.class), mock(AclTracker.class), mock(ContentTracker.class)));
    }
}
