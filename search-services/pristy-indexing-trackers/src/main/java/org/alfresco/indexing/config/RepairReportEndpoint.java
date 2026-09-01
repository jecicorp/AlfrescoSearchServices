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

package org.alfresco.indexing.config;

import org.alfresco.indexing.tracker.repair.RepairReport;
import org.alfresco.indexing.tracker.repair.RepairTracker;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "repairreport")
public class RepairReportEndpoint
{
    private final TrackerBootstrap bootstrap;

    public RepairReportEndpoint(TrackerBootstrap bootstrap)
    {
        this.bootstrap = bootstrap;
    }

    @ReadOperation
    public Map<String, Object> repairReport()
    {
        RepairTracker tracker = bootstrap.getRepairTracker();
        if (tracker == null)
        {
            return Map.of("error", "RepairTracker not yet initialised");
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
