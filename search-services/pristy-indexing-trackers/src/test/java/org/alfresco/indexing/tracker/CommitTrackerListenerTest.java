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

package org.alfresco.indexing.tracker;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Semaphore;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.TrackerState;
import org.junit.Before;
import org.junit.Test;

public class CommitTrackerListenerTest
{
    private static final String CORE = "alfresco";

    private final InformationServer infoSrv = mock(InformationServer.class);
    private final MetadataTracker metadataTracker = mock(MetadataTracker.class);
    private final AclTracker aclTracker = mock(AclTracker.class);
    private final ContentTracker contentTracker = mock(ContentTracker.class);
    private final Semaphore metadataLock = new Semaphore(1);
    private final Semaphore aclLock = new Semaphore(1);
    private final List<String> committed = new ArrayList<>();

    @Before
    public void setUp()
    {
        when(metadataTracker.getWriteLock()).thenReturn(metadataLock);
        when(aclTracker.getWriteLock()).thenReturn(aclLock);
        when(contentTracker.getWriteLock()).thenReturn(new Semaphore(1));
        when(metadataTracker.isEnabled()).thenReturn(true);
        when(aclTracker.isEnabled()).thenReturn(true);
        when(metadataTracker.getTrackerState()).thenReturn(new TrackerState());
    }

    @Test
    public void aCommitNotifiesTheListenersWithTheCore() throws Throwable
    {
        CommitTracker tracker = tracker("-1");
        tracker.addCommitListener(committed::add);

        tracker.doTrack("IT #1");

        verify(infoSrv).commit(anyBoolean());
        assertEquals(List.of(CORE), committed);
    }

    @Test
    public void aFailingListenerStopsNeitherTheOthersNorTheCommit() throws Throwable
    {
        CommitTracker tracker = tracker("-1");
        tracker.addCommitListener(core -> {
            throw new IllegalStateException("listener bug");
        });
        tracker.addCommitListener(committed::add);

        tracker.doTrack("IT #1");

        verify(infoSrv).commit(anyBoolean());
        assertEquals(List.of(CORE), committed);
    }

    @Test
    public void theListenersRunOnceTheWriteLocksAreReleased() throws Throwable
    {
        CommitTracker tracker = tracker("-1");
        List<Integer> permits = new ArrayList<>();
        tracker.addCommitListener(core -> permits.add(metadataLock.availablePermits() + aclLock.availablePermits()));

        tracker.doTrack("IT #1");

        assertEquals(List.of(2), permits);
    }

    @Test
    public void anErrorFromAListenerStopsNeitherTheOthersNorTheCommit() throws Throwable
    {
        CommitTracker tracker = tracker("-1");
        tracker.addCommitListener(core -> {
            throw new AssertionError("listener error");
        });
        tracker.addCommitListener(committed::add);

        tracker.doTrack("IT #1");

        verify(infoSrv).commit(anyBoolean());
        verify(infoSrv, never()).rollback();
        assertEquals(List.of(CORE), committed);
    }

    @Test(expected = NullPointerException.class)
    public void aNullListenerIsRefused()
    {
        tracker("-1").addCommitListener(null);
    }

    @Test
    public void noCommitMeansNoNotification() throws Throwable
    {
        CommitTracker tracker = tracker("3600000");
        tracker.addCommitListener(committed::add);

        tracker.doTrack("IT #1");

        verify(infoSrv, never()).commit(anyBoolean());
        assertEquals(List.of(), committed);
    }

    @Test
    public void aRollbackIsNotACommit() throws Throwable
    {
        when(metadataTracker.getRollback()).thenReturn(true);
        CommitTracker tracker = tracker("-1");
        tracker.addCommitListener(committed::add);

        tracker.doTrack("IT #1");

        verify(infoSrv).rollback();
        verify(infoSrv, never()).commit(anyBoolean());
        assertEquals(List.of(), committed);
    }

    @Test
    public void theRegisteredListenersAreListed()
    {
        CommitTracker tracker = tracker("-1");
        CommitListener listener = committed::add;

        tracker.addCommitListener(listener);

        assertEquals(List.of(listener), tracker.getCommitListeners());
    }

    private CommitTracker tracker(String commitInterval)
    {
        Properties properties = new Properties();
        properties.setProperty("alfresco.commitInterval", commitInterval);
        return new CommitTracker(properties, null, CORE, infoSrv,
                List.of(metadataTracker, aclTracker, contentTracker));
    }
}
