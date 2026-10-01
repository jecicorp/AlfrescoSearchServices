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

/**
 * A node as the repository database knows it, for an index await request.
 * @param dbid   the node DBID
 * @param status whether the node is live in the awaited store
 * @param tx     the transaction that last changed the node, {@code -1} unless live
 */
public record DatabaseNode(long dbid, Status status, long tx)
{
    /** What the repository answered for the node. */
    public enum Status
    {
        LIVE,
        ORPHAN,
        UNREACHABLE
    }
}
