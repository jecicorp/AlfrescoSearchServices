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

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.tracker.CommitListener;
import org.alfresco.indexing.tracker.CommitTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.indexing.tracker.TrackerScheduler;

/**
 * Wakes and observes the trackers of a core through their Quartz jobs, once {@link TrackerBootstrap} started them.
 */
public class QuartzCoreTrackers implements CoreTrackers
{
    private static final String METADATA = MetadataTracker.class.getSimpleName();
    private static final String COMMIT = CommitTracker.class.getSimpleName();

    private final TrackerBootstrap bootstrap;

    /**
     * @param bootstrap the owner of the tracker scheduler and registry
     */
    public QuartzCoreTrackers(TrackerBootstrap bootstrap)
    {
        this.bootstrap = bootstrap;
    }

    @Override
    public boolean hook(String core, Runnable metadataRunStarted, Runnable metadataRunEnded,
            CommitListener commitListener)
    {
        TrackerScheduler scheduler = bootstrap.getScheduler();
        TrackerRegistry registry = bootstrap.getRegistry();
        if (scheduler == null || registry == null)
        {
            return false;
        }
        CommitTracker commitTracker = registry.getTrackerForCore(core, CommitTracker.class);
        if (commitTracker == null)
        {
            return false;
        }
        if (!scheduler.onTriggeredRun(METADATA, core, metadataRunStarted, metadataRunEnded))
        {
            return false;
        }
        commitTracker.addCommitListener(commitListener);
        return true;
    }

    @Override
    public boolean triggerMetadata(String core, long delayMillis)
    {
        TrackerScheduler scheduler = bootstrap.getScheduler();
        return scheduler != null && scheduler.triggerOnce(METADATA, core, delayMillis);
    }

    @Override
    public boolean triggerCommit(String core)
    {
        TrackerScheduler scheduler = bootstrap.getScheduler();
        return scheduler != null && scheduler.triggerOnce(COMMIT, core, 0L);
    }
}
