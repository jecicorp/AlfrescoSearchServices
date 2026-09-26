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

package org.alfresco.indexing.diagnostic;

/**
 * Receives the progress of one phase of an index check, once before each batch and once at its end.
 */
@FunctionalInterface
public interface ProgressListener
{
    ProgressListener NONE = (current, target) -> { };

    /**
     * @param current how far the phase got, never decreasing within one phase
     * @param target  where the phase ends
     */
    void onProgress(long current, long target);

    /**
     * @param bound   the upper bound of a phase, or {@code null} when none is known
     * @param current how far the phase got
     * @return {@code bound}, or {@code current} when no bound is known
     */
    static long target(Long bound, long current)
    {
        return bound != null ? bound : current;
    }
}
