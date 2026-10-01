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

import java.util.List;

/**
 * One event of an index await stream; its record components are the JSON {@code data} of the event.
 */
public sealed interface AwaitEvent permits AwaitEvent.Searchable, AwaitEvent.Failed, AwaitEvent.End
{
    /** @return the SSE event name */
    String eventName();

    /**
     * The node is returned by a query on the open searcher at or past its repository transaction.
     *
     * @param dbid the node DBID
     */
    record Searchable(long dbid) implements AwaitEvent
    {
        @Override
        public String eventName()
        {
            return "searchable";
        }
    }

    /**
     * The node will not become searchable.
     *
     * @param dbid    the node DBID
     * @param verdict why, as a verdict name: {@code ERROR}, {@code UNINDEXED}, {@code ORPHAN} or {@code UNREACHABLE}
     */
    record Failed(long dbid, String verdict) implements AwaitEvent
    {
        @Override
        public String eventName()
        {
            return "error";
        }
    }

    /**
     * The last event of a stream.
     *
     * @param pending the DBIDs that got no event before the timeout, empty when every one did
     */
    record End(List<Long> pending) implements AwaitEvent
    {
        @Override
        public String eventName()
        {
            return "end";
        }
    }
}
