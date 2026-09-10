/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Indexing status of a single repository node, as served by
 * {@code GET /api/v1/index/node}.
 *
 * @param reference how the caller designated the node and what it resolved to
 * @param database  the node as the repository database knows it
 * @param cores     the node as each tracked core knows it, keyed by core name
 * @param verdict   the most informative core state, or {@code unresolved}
 */
public record NodeIndexStatus(Reference reference, Database database,
                              Map<String, Core> cores, Verdict verdict)
{
    /**
     * @param input      the reference exactly as the caller supplied it
     * @param dbid       the resolved node DBID, null when nothing matched
     * @param resolvedBy the field the resolution went through, null when unresolved
     */
    public record Reference(String input, Long dbid, String resolvedBy) {}

    /**
     * @param status the repository node status, null when the lookup failed
     * @param tx     the transaction that last changed the node, null when unknown
     */
    public record Database(DatabaseStatus status, Long tx) {}

    /**
     * @param state    what this core holds for the node
     * @param docType  the Solr DOC_TYPE of the document found, null when absent
     * @param indexTx  the transaction the indexed document carries, null when absent
     * @param aclId    the ACL the indexed document carries, null when absent
     * @param docCount how many documents this core holds for the node
     */
    public record Core(State state, String docType, Long indexTx, Long aclId, long docCount) {}

    /** What a single core holds for the node. */
    public enum State
    {
        INDEXED("indexed"),
        STALE("stale"),
        ERROR("error"),
        UNINDEXED("unindexed"),
        ABSENT("absent");

        private final String value;

        State(String value)
        {
            this.value = value;
        }

        @JsonValue
        public String value()
        {
            return value;
        }
    }

    /** The node as the repository database knows it. */
    public enum DatabaseStatus
    {
        UPDATED("updated"),
        DELETED("deleted"),
        UNKNOWN("unknown"),
        UNREACHABLE("unreachable");

        private final String value;

        DatabaseStatus(String value)
        {
            this.value = value;
        }

        @JsonValue
        public String value()
        {
            return value;
        }
    }

    /** The overall answer, derived from every core state. */
    public enum Verdict
    {
        INDEXED("indexed"),
        STALE("stale"),
        ERROR("error"),
        UNINDEXED("unindexed"),
        MISSING("missing"),
        UNRESOLVED("unresolved");

        private final String value;

        Verdict(String value)
        {
            this.value = value;
        }

        @JsonValue
        public String value()
        {
            return value;
        }
    }
}
