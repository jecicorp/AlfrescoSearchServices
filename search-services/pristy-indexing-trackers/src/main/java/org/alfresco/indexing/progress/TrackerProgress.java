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

import java.time.Instant;

/**
 * A tracker's progress as served to clients.
 *
 * @param tracker the tracker's short name
 * @param active whether the tracker is currently enabled
 * @param done the tracker's cursor, {@code null} when it keeps no countable cursor
 * @param remaining the backlog in the tracker's own unit, {@code null} when uncountable
 * @param remainingNodes the backlog expressed in nodes, an estimate derived from the mean
 *                       document count per unit, {@code null} when it cannot be derived
 * @param peakRemaining the worst backlog seen since the service started, which gives a
 *                      denominator to a tracker that keeps no cursor
 * @param ratePerSec units indexed per second, {@code null} when not measured yet
 * @param etaSeconds seconds before the backlog clears, {@code null} when unknown
 * @param trend whether the throughput is picking up or dropping off, {@code null} until
 *              there are enough samples to tell
 * @param lastSampleAt when the reading behind these figures was taken
 */
public record TrackerProgress(String tracker, boolean active, Long done, Long remaining,
        Long remainingNodes, Long peakRemaining, Double ratePerSec, Long etaSeconds,
        ProgressRate.Trend trend, Instant lastSampleAt)
{
}
