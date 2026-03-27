/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
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

package org.alfresco.indexing.config;

import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.indexing.tracker.repair.RepairReport;
import org.alfresco.indexing.tracker.repair.RepairTracker;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "repair-report")
public class RepairReportEndpoint
{
    private final TrackerRegistry registry;
    private final String coreName;

    public RepairReportEndpoint(TrackerRegistry registry, TrackerProperties props)
    {
        this.registry = registry;
        this.coreName = props.getSolr().getCollection();
    }

    @ReadOperation
    public Map<String, Object> repairReport()
    {
        RepairTracker tracker = registry.getTrackerForCore(coreName, RepairTracker.class);
        if (tracker == null)
        {
            return Map.of("error", "RepairTracker not registered");
        }

        RepairReport report = tracker.getReport();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastCycleTimestamp", report.getLastCycleTimestamp());
        result.put("totalErrorNodes", report.getTotalErrorNodes());
        result.put("repairedThisCycle", report.getRepairedThisCycle());
        result.put("pendingErrors", report.getPendingErrors());
        result.put("recentRepairs", report.getRecentRepairs());
        result.put("summary", report.getSummary());
        return result;
    }
}
