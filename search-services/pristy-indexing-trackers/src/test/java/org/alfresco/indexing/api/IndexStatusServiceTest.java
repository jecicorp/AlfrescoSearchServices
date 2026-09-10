/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
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

package org.alfresco.indexing.api;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ERROR_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_UNINDEXED_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_ACLID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DBID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DOC_TYPE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_INTXID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Set;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class IndexStatusServiceTest
{
    private static final String UUID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";
    private static final long DBID = 15695L;

    private SolrClient solrClient;
    private TrackerRegistry registry;
    private MetadataTracker metadataTracker;
    private IndexStatusService service;

    @Before
    public void setUp()
    {
        solrClient = mock(SolrClient.class);
        registry = mock(TrackerRegistry.class);
        metadataTracker = mock(MetadataTracker.class);
        TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);

        when(bootstrap.getRegistry()).thenReturn(registry);
        cores("alfresco");
        when(registry.getTrackerForCore(any(), eq(MetadataTracker.class))).thenReturn(metadataTracker);

        service = new IndexStatusService(bootstrap, solrClient);
    }

    private void cores(String... names)
    {
        when(registry.getCoreNames()).thenReturn(new LinkedHashSet<>(Set.of(names)));
    }

    private void databaseHolds(long tx, SolrApiNodeStatus status)
    {
        NodeReport report = new NodeReport();
        report.setDbid(DBID);
        report.setDbTx(tx);
        report.setDbNodeStatus(status);
        when(metadataTracker.checkNode(DBID)).thenReturn(report);
    }

    private static SolrDocumentList documents(SolrDocument... docs)
    {
        SolrDocumentList list = new SolrDocumentList();
        for (SolrDocument doc : docs)
        {
            list.add(doc);
        }
        list.setNumFound(docs.length);
        return list;
    }

    private static SolrDocument nodeDocument(String docType, Long intxid)
    {
        SolrDocument doc = new SolrDocument();
        doc.addField(FIELD_DOC_TYPE, docType);
        if (intxid != null)
        {
            doc.addField(FIELD_INTXID, intxid);
        }
        doc.addField(FIELD_ACLID, 42L);
        return doc;
    }

    private void indexHolds(String core, SolrDocumentList docs) throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(eq(core), any(SolrQuery.class))).thenReturn(response);
    }

    @Test
    public void aNumericReferenceIsTakenAsADbidWithoutQueryingTheIndexForIt() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(Long.valueOf(DBID), status.reference().dbid());
        assertEquals("DBID", status.reference().resolvedBy());
    }

    @Test
    public void aUuidIsResolvedThroughTheIndexAgainstBothDefaultStores() throws Exception
    {
        SolrDocument resolution = new SolrDocument();
        resolution.addField(FIELD_DBID, DBID);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(documents(resolution),
                documents(nodeDocument(DOC_TYPE_NODE, 4711L)));
        when(solrClient.query(eq("alfresco"), any(SolrQuery.class))).thenReturn(response);
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);

        NodeIndexStatus status = service.status(UUID);

        assertEquals(Long.valueOf(DBID), status.reference().dbid());
        assertEquals("LID", status.reference().resolvedBy());

        ArgumentCaptor<SolrQuery> queries = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient, org.mockito.Mockito.atLeastOnce()).query(eq("alfresco"), queries.capture());
        String resolutionQuery = queries.getAllValues().get(0).getQuery();
        assertTrue(resolutionQuery, resolutionQuery.contains("workspace://SpacesStore/" + UUID));
        assertTrue(resolutionQuery, resolutionQuery.contains("archive://SpacesStore/" + UUID));
    }

    @Test
    public void aFullNodeReferenceIsUsedVerbatim() throws Exception
    {
        SolrDocument resolution = new SolrDocument();
        resolution.addField(FIELD_DBID, DBID);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(documents(resolution), documents());
        when(solrClient.query(eq("alfresco"), any(SolrQuery.class))).thenReturn(response);
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);

        service.status("archive://SpacesStore/" + UUID);

        ArgumentCaptor<SolrQuery> queries = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient, org.mockito.Mockito.atLeastOnce()).query(eq("alfresco"), queries.capture());
        String resolutionQuery = queries.getAllValues().get(0).getQuery();
        assertTrue(resolutionQuery, resolutionQuery.contains("archive://SpacesStore/" + UUID));
        assertTrue(resolutionQuery, !resolutionQuery.contains("workspace://SpacesStore/"));
    }

    @Test
    public void aMalformedReferenceIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> service.status("not-a-node"));
        assertThrows(IllegalArgumentException.class, () -> service.status("   "));
    }

    @Test
    public void anUnmatchedUuidIsUnresolvedAndTouchesNoTracker() throws Exception
    {
        indexHolds("alfresco", documents());

        NodeIndexStatus status = service.status(UUID);

        assertEquals(NodeIndexStatus.Verdict.UNRESOLVED, status.verdict());
        assertNull(status.reference().dbid());
        assertNull(status.reference().resolvedBy());
        assertTrue(status.cores().isEmpty());
        verify(metadataTracker, never()).checkNode(any(Long.class));
    }

    @Test
    public void aNodeDocumentCarryingTheDatabaseTransactionIsIndexed() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.INDEXED, status.verdict());
        assertEquals(NodeIndexStatus.State.INDEXED, status.cores().get("alfresco").state());
        assertEquals(Long.valueOf(4711L), status.cores().get("alfresco").indexTx());
        assertEquals(Long.valueOf(42L), status.cores().get("alfresco").aclId());
    }

    @Test
    public void aNodeDocumentLaggingTheDatabaseTransactionIsStale() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 17L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.STALE, status.verdict());
    }

    @Test
    public void anErrorDocumentIsReportedAsAnIndexingError() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_ERROR_NODE, null)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.ERROR, status.verdict());
    }

    @Test
    public void anUnindexedDocumentIsReportedAsADeliberateExclusion() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_UNINDEXED_NODE, null)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.UNINDEXED, status.verdict());
    }

    @Test
    public void aNodeTheIndexDoesNotHoldIsMissing() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco", documents());

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.MISSING, status.verdict());
        assertEquals(NodeIndexStatus.State.ABSENT, status.cores().get("alfresco").state());
        assertEquals(0L, status.cores().get("alfresco").docCount());
    }

    @Test
    public void theNodeDocumentWinsOverAnErrorDocumentOnTheSameCore() throws Exception
    {
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("alfresco",
                documents(nodeDocument(DOC_TYPE_ERROR_NODE, null), nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.State.INDEXED, status.cores().get("alfresco").state());
        assertEquals(2L, status.cores().get("alfresco").docCount());
    }

    @Test
    public void theVerdictTakesTheMostInformativeCore() throws Exception
    {
        cores("archive", "alfresco");
        databaseHolds(4711L, SolrApiNodeStatus.UPDATED);
        indexHolds("archive", documents());
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.Verdict.INDEXED, status.verdict());
        assertEquals(NodeIndexStatus.State.ABSENT, status.cores().get("archive").state());
    }

    @Test
    public void aNegativeDatabaseTransactionMeansTheRepositoryCouldNotBeReached() throws Exception
    {
        databaseHolds(-2L, SolrApiNodeStatus.UNKNOWN);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.DatabaseStatus.UNREACHABLE, status.database().status());
        assertNull(status.database().tx());
    }

    @Test
    public void aNodeUnknownToTheDatabaseStillReportsItsIndexState() throws Exception
    {
        databaseHolds(0L, SolrApiNodeStatus.UNKNOWN);
        indexHolds("alfresco", documents(nodeDocument(DOC_TYPE_NODE, 4711L)));

        NodeIndexStatus status = service.status(String.valueOf(DBID));

        assertEquals(NodeIndexStatus.DatabaseStatus.UNKNOWN, status.database().status());
        assertEquals(NodeIndexStatus.State.INDEXED, status.cores().get("alfresco").state());
    }
}
