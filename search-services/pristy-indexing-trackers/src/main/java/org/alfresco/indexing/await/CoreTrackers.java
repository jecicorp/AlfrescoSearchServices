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

import org.alfresco.indexing.tracker.CommitListener;

/**
 * The trackers of a core, as the index await service wakes and observes them.
 */
public interface CoreTrackers
{
    /**
     * @param core               the core to observe
     * @param metadataRunStarted called when a metadata run fired by {@link #triggerMetadata} starts
     * @param metadataRunEnded   called when such a run ends
     * @param commitListener     called after each commit of the core
     * @return {@code false} while the trackers of the core are not running
     */
    boolean hook(String core, Runnable metadataRunStarted, Runnable metadataRunEnded, CommitListener commitListener);

    /**
     * @param core        the core whose metadata tracker to wake
     * @param delayMillis how long to wait before the run
     * @return {@code false} when the run could not be scheduled
     */
    boolean triggerMetadata(String core, long delayMillis);

    /**
     * @param core the core whose commit tracker to wake at once
     * @return {@code false} when the run could not be scheduled
     */
    boolean triggerCommit(String core);
}
