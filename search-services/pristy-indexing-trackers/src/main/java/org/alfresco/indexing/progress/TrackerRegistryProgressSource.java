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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.solrj.SolrJInformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.CascadeTracker;
import org.alfresco.indexing.tracker.ContentTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.indexing.tracker.repair.RepairReport;
import org.alfresco.indexing.tracker.repair.RepairTracker;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.tracker.TrackerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads progress from the running trackers.
 *
 * <p>Each tracker contributes a cursor and a backlog when it keeps countable ones. The
 * content backlog is read from the index rather than from the tracker, which keeps no
 * cursor of its own; cascade and repair have a backlog but no cursor at all, so they
 * report a remainder and no speed.
 */
public class TrackerRegistryProgressSource implements ProgressSource
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackerRegistryProgressSource.class);

    private static final String CONTENT_IN_SYNC = "Node count whose content is in sync";
    private static final String CONTENT_OUTDATED = "Node count whose content needs to be updated";

    private final TrackerBootstrap bootstrap;

    public TrackerRegistryProgressSource(TrackerBootstrap bootstrap)
    {
        this.bootstrap = bootstrap;
    }

    @Override
    public List<TrackerReading> read()
    {
        TrackerRegistry registry = bootstrap.getRegistry();
        List<TrackerReading> readings = new ArrayList<>();

        for (String core : new TreeSet<>(registry.getCoreNames()))
        {
            try
            {
                readCore(registry, core, readings);
            }
            catch (Exception e)
            {
                LOGGER.debug("No progress read for core {}", core, e);
            }
        }
        return List.copyOf(readings);
    }

    private void readCore(TrackerRegistry registry, String core, List<TrackerReading> readings)
    {
        SolrJInformationServer informationServer = bootstrap.getInformationServer(core);
        TrackerStats stats = informationServer == null ? null : informationServer.getTrackerStats();

        MetadataTracker metadata = registry.getTrackerForCore(core, MetadataTracker.class);
        if (metadata != null)
        {
            TrackerState state = metadata.getTrackerState();
            readings.add(new TrackerReading(core, "metadata", metadata.isEnabled(),
                    state == null ? null : state.getLastIndexedTxId(),
                    state == null ? null : backlog(state.getLastTxIdOnServer(), state.getLastIndexedTxId()),
                    stats == null ? null : stats.getMeanDocsPerTx()));
        }

        AclTracker acl = registry.getTrackerForCore(core, AclTracker.class);
        if (acl != null)
        {
            TrackerState state = acl.getTrackerState();
            readings.add(new TrackerReading(core, "acl", acl.isEnabled(),
                    state == null ? null : state.getLastIndexedChangeSetId(),
                    state == null ? null
                            : backlog(state.getLastChangeSetIdOnServer(), state.getLastIndexedChangeSetId()),
                    stats == null ? null : stats.getMeanAclsPerChangeSet()));
        }

        ContentTracker content = registry.getTrackerForCore(core, ContentTracker.class);
        if (content != null)
        {
            Map<String, Object> counts = new LinkedHashMap<>();
            if (informationServer != null)
            {
                informationServer.addContentOutdatedAndUpdatedCounts(counts);
            }
            readings.add(new TrackerReading(core, "content", content.isEnabled(),
                    count(counts, CONTENT_IN_SYNC), count(counts, CONTENT_OUTDATED), null));
        }

        CascadeTracker cascade = registry.getTrackerForCore(core, CascadeTracker.class);
        if (cascade != null)
        {
            readings.add(new TrackerReading(core, "cascade", cascade.isEnabled(), null,
                    pendingCascades(informationServer), null));
        }

        RepairTracker repair = registry.getTrackerForCore(core, RepairTracker.class);
        if (repair != null)
        {
            RepairReport report = repair.getReport();
            readings.add(new TrackerReading(core, "repair", repair.isEnabled(), null,
                    report == null ? null : (long) report.getTotalErrorNodes(), null));
        }
    }

    private static Long pendingCascades(SolrJInformationServer informationServer)
    {
        if (informationServer == null)
        {
            return null;
        }
        try
        {
            return informationServer.getPendingCascadeCount();
        }
        catch (Exception e)
        {
            LOGGER.debug("No cascade backlog read", e);
            return null;
        }
    }

    private static Long backlog(long onServer, long indexed)
    {
        return Math.max(0L, onServer - indexed);
    }

    private static Long count(Map<String, Object> counts, String key)
    {
        Object value = counts.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }
}
