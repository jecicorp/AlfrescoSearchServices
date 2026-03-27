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
package org.alfresco.indexing.tracker.repair;

import org.junit.Test;
import static org.junit.Assert.*;

public class RepairReportTest
{
    @Test
    public void recordSuccess_addsToRecentRepairs()
    {
        RepairReport report = new RepairReport();
        report.recordResult(new RepairResult(100L, true, "UNRESOLVED_MODEL", "Fixed"));
        assertEquals(1, report.getRecentRepairs().size());
        assertEquals(0, report.getPendingErrors().size());
        assertEquals(100L, report.getRecentRepairs().get(0).dbId());
    }

    @Test
    public void recordFailure_addsToPendingErrors()
    {
        RepairReport report = new RepairReport();
        report.recordResult(new RepairResult(200L, false, "UNRESOLVED_MODEL", "Still broken"));
        assertEquals(0, report.getRecentRepairs().size());
        assertEquals(1, report.getPendingErrors().size());
        assertEquals(1, report.getAttemptCount(200L));
    }

    @Test
    public void recordFailure_incrementsAttemptCount()
    {
        RepairReport report = new RepairReport();
        report.recordResult(new RepairResult(200L, false, "UNRESOLVED_MODEL", "Still broken"));
        report.recordResult(new RepairResult(200L, false, "UNRESOLVED_MODEL", "Still broken"));
        assertEquals(2, report.getAttemptCount(200L));
    }

    @Test
    public void recordSuccess_clearsPendingError()
    {
        RepairReport report = new RepairReport();
        report.recordResult(new RepairResult(200L, false, "UNRESOLVED_MODEL", "Broken"));
        assertEquals(1, report.getPendingErrors().size());
        report.recordResult(new RepairResult(200L, true, "UNRESOLVED_MODEL", "Fixed"));
        assertEquals(0, report.getPendingErrors().size());
        assertEquals(1, report.getRecentRepairs().size());
    }

    @Test
    public void recentRepairs_cappedAt100()
    {
        RepairReport report = new RepairReport();
        for (int i = 0; i < 120; i++)
        {
            report.recordResult(new RepairResult(i, true, "TEST", "ok"));
        }
        assertEquals(100, report.getRecentRepairs().size());
        assertEquals(119L, report.getRecentRepairs().get(report.getRecentRepairs().size() - 1).dbId());
    }

    @Test
    public void pendingErrors_cappedAt500()
    {
        RepairReport report = new RepairReport();
        for (int i = 0; i < 600; i++)
        {
            report.recordResult(new RepairResult(i, false, "TEST", "fail"));
        }
        assertTrue(report.getPendingErrors().size() <= 500);
    }

    @Test
    public void summary_countsPerCategory()
    {
        RepairReport report = new RepairReport();
        report.recordResult(new RepairResult(1L, true, "UNRESOLVED_MODEL", "ok"));
        report.recordResult(new RepairResult(2L, false, "UNRESOLVED_MODEL", "fail"));
        report.recordResult(new RepairResult(3L, true, "EMPTY_NODE", "ok"));

        var summary = report.getSummary();
        assertEquals(1, summary.get("UNRESOLVED_MODEL").pending());
        assertEquals(1, summary.get("UNRESOLVED_MODEL").repaired());
        assertEquals(0, summary.get("EMPTY_NODE").pending());
        assertEquals(1, summary.get("EMPTY_NODE").repaired());
    }
}
