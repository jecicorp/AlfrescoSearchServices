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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Body of {@code POST /api/v1/index/await}.
 * @param dbids   the nodes to wait for
 * @param timeout how long to wait, in milliseconds; {@code null} for the default
 */
public record AwaitRequest(List<Long> dbids, Long timeout)
{
    static final long DEFAULT_TIMEOUT_MILLIS = 20000L;

    /**
     * @param maxTimeoutMillis the upper bound of the timeout
     * @param maxBatch         the most distinct DBIDs one request may carry
     * @return the distinct DBIDs in request order and the effective timeout
     * @throws InvalidAwaitRequestException when the request breaks the contract
     */
    public Accepted accept(long maxTimeoutMillis, int maxBatch)
    {
        if (dbids == null || dbids.isEmpty())
        {
            throw new InvalidAwaitRequestException("dbids must list at least one DBID.");
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long dbid : dbids)
        {
            if (dbid == null || dbid <= 0L)
            {
                throw new InvalidAwaitRequestException("Every DBID must be a positive integer.");
            }
            distinct.add(dbid);
        }
        if (distinct.size() > maxBatch)
        {
            throw new InvalidAwaitRequestException(
                    String.format("At most %d DBIDs per request, got %d.", maxBatch, distinct.size()));
        }
        if (timeout != null && timeout <= 0L)
        {
            throw new InvalidAwaitRequestException("timeout must be a positive number of milliseconds.");
        }
        long requested = timeout == null ? DEFAULT_TIMEOUT_MILLIS : timeout;
        return new Accepted(Collections.unmodifiableSet(distinct), Math.min(requested, maxTimeoutMillis));
    }

    /**
     * A request that passed validation.
     *
     * @param dbids         the distinct DBIDs, in request order
     * @param timeoutMillis the timeout once capped
     */
    public record Accepted(Set<Long> dbids, long timeoutMillis)
    {
    }
}
