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

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON body of the {@code DIAGNOSTIC!LAST} document of one core. Unknown fields are ignored so a
 * document written by another version still reads.
 *
 * @param startedAt       ISO-8601 instant the diagnostic started
 * @param startedBy       who started it
 * @param finishedAt      ISO-8601 instant it finished
 * @param report          this core's {@code REPORT} section
 * @param errorNodes      the repair report of the same run
 * @param partialFailures the result sections that could not be built
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredDiagnostic(String startedAt, String startedBy, String finishedAt,
        Map<String, Object> report, Map<String, Object> errorNodes, List<String> partialFailures)
{
}
