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

package org.alfresco.indexing.api;

import java.util.Map;

/**
 * Answer of {@code GET /api/v1}: what this trackers service is, and what it can do.
 *
 * @param service      artifact name, constant
 * @param version      the running build's version, {@code unknown} when the jar carries none
 * @param api          the major version of this API surface
 * @param capabilities keyed by capability name
 */
public record ApiDescriptor(String service, String version, String api,
                            Map<String, State> capabilities)
{
    /**
     * @param since   the service version the capability first shipped in
     * @param enabled whether it is currently turned on
     */
    public record State(String since, boolean enabled) {}
}
