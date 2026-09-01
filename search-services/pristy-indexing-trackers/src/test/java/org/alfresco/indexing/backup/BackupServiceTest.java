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
package org.alfresco.indexing.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.alfresco.indexing.config.TrackerProperties;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BackupServiceTest
{
    @Mock SolrClient solrClient;
    private TrackerProperties props;
    private BackupService service;

    /** backup/restore trigger requests captured by the stub, in order. */
    private final List<SolrRequest<?>> triggers = new ArrayList<>();
    /** Successive responses per status command; the last one repeats. */
    private final Queue<NamedList<Object>> detailsResponses = new ArrayDeque<>();
    private final Queue<NamedList<Object>> restoreStatusResponses = new ArrayDeque<>();

    @Before
    public void setUp() throws Exception
    {
        MockitoAnnotations.openMocks(this);
        props = new TrackerProperties();
        props.getBackup().setLocation("/data/backup");
        props.getBackup().setNumberToKeep(4);
        props.getBackup().setPollIntervalMillis(1);
        props.getBackup().setPollTimeoutSeconds(2);
        service = new BackupService(solrClient, props);

        when(solrClient.request(any(SolrRequest.class), isNull())).thenAnswer(invocation -> {
            SolrRequest<?> request = invocation.getArgument(0);
            String command = request.getParams().get("command");
            switch (command)
            {
            case "backup":
            case "restore":
                triggers.add(request);
                return new NamedList<>();
            case "details":
                return nextOrLast(detailsResponses);
            case "restorestatus":
                return nextOrLast(restoreStatusResponses);
            default:
                return new NamedList<>();
            }
        });
    }

    private static NamedList<Object> nextOrLast(Queue<NamedList<Object>> queue)
    {
        if (queue.isEmpty())
        {
            return new NamedList<>();
        }
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    /** Wraps a backup outcome as Solr publishes it: {details: {backup: {...}}}. */
    private static NamedList<Object> detailsWithBackup(Object... keyValues)
    {
        NamedList<Object> response = new NamedList<>();
        NamedList<Object> details = new NamedList<>();
        details.add("backup", namedList(keyValues));
        response.add("details", details);
        return response;
    }

    /** Wraps a restore outcome as Solr publishes it: {restorestatus: {...}}. */
    private static NamedList<Object> withRestoreStatus(Object... keyValues)
    {
        NamedList<Object> response = new NamedList<>();
        response.add("restorestatus", namedList(keyValues));
        return response;
    }

    private static NamedList<Object> namedList(Object... keyValues)
    {
        NamedList<Object> namedList = new NamedList<>();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            namedList.add((String) keyValues[i], keyValues[i + 1]);
        }
        return namedList;
    }

    @Test
    public void backupCore_issuesReplicationBackupRequest() throws Exception
    {
        detailsResponses.add(new NamedList<>()); // baseline: no backup yet
        detailsResponses.add(detailsWithBackup("startTime", "t1", "status", "success",
                "snapshotName", "snapshot.20260717"));

        Map<String, Object> result = service.backupCore("alfresco", "/data/backup", 4);

        assertEquals(1, triggers.size());
        SolrRequest<?> req = triggers.get(0);
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("backup", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
        assertEquals("4", req.getParams().get("numberToKeep"));
        assertEquals("ok", result.get("status"));
        assertEquals("snapshot.20260717", result.get("snapshotName"));
    }

    @Test
    public void backupCore_reportsAsyncSnapshotFailure() throws Exception
    {
        detailsResponses.add(new NamedList<>()); // baseline: no backup yet
        detailsResponses.add(detailsWithBackup("startTime", "t1",
                "exception", "No space left on device", "status", "failed"));

        Map<String, Object> result = service.backupCore("alfresco", null, null);

        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("errorMessage")).contains("No space left on device"));
    }

    @Test
    public void backupCore_ignoresStaleOutcomeFromPreviousBackup() throws Exception
    {
        // The pre-trigger entry (an older successful snapshot) must not be
        // mistaken for the outcome of the backup we just triggered.
        NamedList<Object> stale = detailsWithBackup("startTime", "t0", "status", "success",
                "snapshotName", "snapshot.OLD");
        detailsResponses.add(stale); // baseline
        detailsResponses.add(stale); // first poll: unchanged
        detailsResponses.add(detailsWithBackup("startTime", "t1", "status", "success",
                "snapshotName", "snapshot.NEW"));

        Map<String, Object> result = service.backupCore("alfresco", null, null);

        assertEquals("ok", result.get("status"));
        assertEquals("snapshot.NEW", result.get("snapshotName"));
    }

    @Test
    public void restoreCore_issuesReplicationRestoreRequest() throws Exception
    {
        restoreStatusResponses.add(withRestoreStatus("snapshotName", "snapshot.20260625",
                "status", "In Progress"));
        restoreStatusResponses.add(withRestoreStatus("snapshotName", "snapshot.20260625",
                "status", "success"));

        Map<String, Object> result = service.restoreCore("alfresco", "/data/backup", "snapshot.20260625");

        assertEquals(1, triggers.size());
        SolrRequest<?> req = triggers.get(0);
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("restore", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
        assertEquals("snapshot.20260625", req.getParams().get("name"));
        assertEquals("ok", result.get("status"));
    }

    @Test
    public void restoreCore_nullLocation_fallsBackToResolvedCoreLocation() throws Exception
    {
        restoreStatusResponses.add(withRestoreStatus("snapshotName", "snapshot.1", "status", "success"));

        // location omitted -> resolved from per-core (here: global) backup config
        Map<String, Object> result = service.restoreCore("alfresco", null, null);

        assertEquals(1, triggers.size());
        SolrRequest<?> req = triggers.get(0);
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("restore", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
        assertEquals("ok", result.get("status"));
    }

    @Test
    public void restoreCore_reportsAsyncRestoreFailure() throws Exception
    {
        restoreStatusResponses.add(withRestoreStatus("snapshotName", "snapshot.1",
                "status", "failed", "exception", "Index fetch failed"));

        Map<String, Object> result = service.restoreCore("alfresco", null, null);

        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("errorMessage")).contains("Index fetch failed"));
    }
}
