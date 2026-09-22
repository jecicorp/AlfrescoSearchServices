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
 * What one tracker reports about itself at one instant, before any derivation.
 *
 * @param core the Solr core the tracker feeds
 * @param tracker the tracker's short name, as {@link ProgressSnapshot} exposes it
 * @param active whether the tracker is currently enabled
 * @param done the tracker's cursor, {@code null} when it keeps no countable cursor
 * @param remaining the backlog, {@code null} when it cannot be counted
 * @param docsPerUnit mean documents per unit of {@code done}, used to express the backlog
 *                    in nodes; {@code null} when the unit already is the node
 */
public record TrackerReading(String core, String tracker, boolean active, Long done,
        Long remaining, Double docsPerUnit)
{
}
