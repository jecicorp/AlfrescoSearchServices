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

/**
 * A tracker's throughput, the time it still needs at that throughput, and where that
 * throughput is heading.
 *
 * @param ratePerSec units indexed per second, {@code null} when no throughput could be
 *                   measured, which a client must render differently from a measured zero
 * @param etaSeconds seconds before the tracker catches up, {@code null} when unknown
 * @param trend whether the throughput is picking up or dropping off, {@code null} until
 *              the window holds enough samples to compare its two halves
 */
public record ProgressRate(Double ratePerSec, Long etaSeconds, Trend trend)
{
    /** Where a throughput is heading, within a dead band that ignores mere noise. */
    public enum Trend
    {
        RISING, STEADY, FALLING
    }

    /** @return a rate carrying no measurement at all */
    public static ProgressRate unknown()
    {
        return new ProgressRate(null, null, null);
    }
}
