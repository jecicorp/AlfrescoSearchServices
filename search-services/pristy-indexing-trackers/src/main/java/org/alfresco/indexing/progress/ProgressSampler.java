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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes one reading of every tracker and publishes the result to the open streams.
 *
 * <p>Every tick publishes, whether the figures moved or not: the event doubles as the
 * keep-alive that stops an idle proxy from closing the stream.
 */
public class ProgressSampler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressSampler.class);

    private final ProgressService service;
    private final ProgressBroadcaster broadcaster;

    public ProgressSampler(ProgressService service, ProgressBroadcaster broadcaster)
    {
        this.service = service;
        this.broadcaster = broadcaster;
    }

    /**
     * Samples, then publishes. A core that is not started yet, or a repository that is
     * briefly unreachable, must leave the cadence running rather than surface as a failed
     * scheduled task every two seconds.
     */
    public void tick()
    {
        try
        {
            service.sample();
            broadcaster.broadcast(service.snapshot());
        }
        catch (Exception e)
        {
            LOGGER.debug("No progress sampled this tick", e);
        }
    }
}
