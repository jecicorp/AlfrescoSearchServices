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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import org.alfresco.indexing.config.TrackerProperties;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BackupServiceTest
{
    @Mock SolrClient solrClient;
    private TrackerProperties props;
    private BackupService service;

    @Before
    public void setUp() throws Exception
    {
        MockitoAnnotations.openMocks(this);
        when(solrClient.request(any(SolrRequest.class), isNull())).thenReturn(new NamedList<>());
        props = new TrackerProperties();
        props.getBackup().setLocation("/data/backup");
        props.getBackup().setNumberToKeep(4);
        service = new BackupService(solrClient, props);
    }

    @Test
    public void backupCore_issuesReplicationBackupRequest() throws Exception
    {
        service.backupCore("alfresco", "/data/backup", 4);

        ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
        org.mockito.Mockito.verify(solrClient).request(captor.capture(), isNull());
        SolrRequest<?> req = captor.getValue();
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("backup", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
        assertEquals("4", req.getParams().get("numberToKeep"));
    }

    @Test
    public void restore_issuesReplicationRestoreRequest() throws Exception
    {
        service.restore("alfresco", "/data/backup", "snapshot.20260625");

        ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
        org.mockito.Mockito.verify(solrClient).request(captor.capture(), isNull());
        SolrRequest<?> req = captor.getValue();
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("restore", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
        assertEquals("snapshot.20260625", req.getParams().get("name"));
    }

    @Test
    public void restore_nullLocation_fallsBackToResolvedCoreLocation() throws Exception
    {
        // location omitted -> resolved from per-core (here: global) backup config
        service.restore("alfresco", null, null);

        ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
        org.mockito.Mockito.verify(solrClient).request(captor.capture(), isNull());
        SolrRequest<?> req = captor.getValue();
        assertEquals("/alfresco/replication", req.getPath());
        assertEquals("restore", req.getParams().get("command"));
        assertEquals("/data/backup/alfresco", req.getParams().get("location"));
    }
}
