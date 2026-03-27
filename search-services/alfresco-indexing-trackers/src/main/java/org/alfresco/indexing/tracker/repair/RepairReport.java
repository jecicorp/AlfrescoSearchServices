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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory report of repair activity. Thread-safe.
 */
public class RepairReport
{
    public record PendingError(long dbId, String tenant, String category,
                               String message, Instant firstSeen, int attempts) {}
    public record RecentRepair(long dbId, String category, Instant repairedAt, String message) {}
    public record CategorySummary(int pending, int repaired) {}

    private static final int MAX_RECENT_REPAIRS = 100;
    private static final int MAX_PENDING_ERRORS = 500;

    private final ConcurrentHashMap<Long, PendingError> pendingByDbId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RecentRepair> recentRepairs = new CopyOnWriteArrayList<>();

    private volatile Instant lastCycleTimestamp;
    private volatile int repairedThisCycle;

    public void startCycle()
    {
        lastCycleTimestamp = Instant.now();
        repairedThisCycle = 0;
    }

    public void recordResult(RepairResult result)
    {
        if (result.success())
        {
            pendingByDbId.remove(result.dbId());
            recentRepairs.add(new RecentRepair(
                    result.dbId(), result.category(), Instant.now(), result.message()));
            while (recentRepairs.size() > MAX_RECENT_REPAIRS)
            {
                recentRepairs.remove(0);
            }
            repairedThisCycle++;
        }
        else
        {
            PendingError existing = pendingByDbId.get(result.dbId());
            int attempts = (existing != null) ? existing.attempts() + 1 : 1;
            Instant firstSeen = (existing != null) ? existing.firstSeen() : Instant.now();
            if (pendingByDbId.size() < MAX_PENDING_ERRORS || existing != null)
            {
                pendingByDbId.put(result.dbId(), new PendingError(
                        result.dbId(), "", result.category(), result.message(), firstSeen, attempts));
            }
        }
    }

    public void markPermanentlyFailed(long dbId) { pendingByDbId.remove(dbId); }

    public int getAttemptCount(long dbId)
    {
        PendingError pe = pendingByDbId.get(dbId);
        return pe != null ? pe.attempts() : 0;
    }

    public List<PendingError> getPendingErrors() { return List.copyOf(pendingByDbId.values()); }
    public List<RecentRepair> getRecentRepairs() { return List.copyOf(recentRepairs); }

    public Map<String, CategorySummary> getSummary()
    {
        Map<String, int[]> counts = new HashMap<>();
        for (PendingError pe : pendingByDbId.values())
            counts.computeIfAbsent(pe.category(), k -> new int[2])[0]++;
        for (RecentRepair rr : recentRepairs)
            counts.computeIfAbsent(rr.category(), k -> new int[2])[1]++;
        Map<String, CategorySummary> summary = new HashMap<>();
        counts.forEach((cat, c) -> summary.put(cat, new CategorySummary(c[0], c[1])));
        return summary;
    }

    public Instant getLastCycleTimestamp() { return lastCycleTimestamp; }
    public int getTotalErrorNodes() { return pendingByDbId.size(); }
    public int getRepairedThisCycle() { return repairedThisCycle; }
}
