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
 * The four phases of one core's index diagnostic, in processing order.
 */
public enum DiagnosticPhase
{
    METADATA_DB("metadata.db"),
    METADATA_INDEX("metadata.index"),
    ACL_DB("acl.db"),
    ACL_INDEX("acl.index");

    private final String wireName;

    DiagnosticPhase(String wireName)
    {
        this.wireName = wireName;
    }

    /** @return the name the snapshot carries in {@code cores.<core>.phase} */
    public String wireName()
    {
        return wireName;
    }
}
