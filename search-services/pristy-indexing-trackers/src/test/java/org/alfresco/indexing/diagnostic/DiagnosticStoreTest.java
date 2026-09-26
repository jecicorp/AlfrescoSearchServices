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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.alfresco.indexing.server.solrj.SolrDocumentMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.json.JsonMapper;

public class DiagnosticStoreTest
{
    private final SolrClient solrClient = mock(SolrClient.class);
    private final DiagnosticStore store = new DiagnosticStore(solrClient, JsonMapper.builder().findAndAddModules().build());

    @Test
    public void savesOneDocumentPerCoreUnderTheFixedId() throws Exception
    {
        store.save("alfresco", stored());

        SolrInputDocument written = written("alfresco");
        assertEquals("DIAGNOSTIC!LAST", written.getFieldValue(SolrDocumentMapper.FIELD_SOLR4_ID));
        assertEquals("Diagnostic", written.getFieldValue(SolrDocumentMapper.FIELD_DOC_TYPE));
    }

    @Test
    public void savingNeverCommits() throws Exception
    {
        store.save("alfresco", stored());

        verify(solrClient, never()).commit();
        verify(solrClient, never()).commit(anyBoolean(), anyBoolean());
        verify(solrClient, never()).commit(anyBoolean(), anyBoolean(), anyBoolean());
        verify(solrClient, never()).commit(anyString());
        verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean());
        verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class), anyInt());
    }

    @Test
    public void readsBackWhatItWrote() throws Exception
    {
        store.save("alfresco", stored());
        String json = written("alfresco").getFieldValue(SolrDocumentMapper.FIELD_DIAGNOSTIC).toString();
        when(solrClient.getById("alfresco", "DIAGNOSTIC!LAST")).thenReturn(document(json));

        assertEquals(Optional.of(stored()), store.load("alfresco"));
    }

    @Test
    public void aCoreWithoutADiagnosticHasNone() throws Exception
    {
        when(solrClient.getById("archive", "DIAGNOSTIC!LAST")).thenReturn(null);

        assertTrue(store.load("archive").isEmpty());
    }

    @Test
    public void aDocumentFromAnOlderShapeKeepsWhatItCanRead() throws Exception
    {
        when(solrClient.getById("alfresco", "DIAGNOSTIC!LAST")).thenReturn(
                document("{\"finishedAt\":\"2026-09-25T15:02:11Z\",\"version\":0,\"summary\":{}}"));

        StoredDiagnostic loaded = store.load("alfresco").orElseThrow();

        assertEquals("2026-09-25T15:02:11Z", loaded.finishedAt());
        assertNull(loaded.startedBy());
        assertNull(loaded.report());
    }

    @Test
    public void aDocumentOfAnotherShapeIsIgnoredRatherThanFailingTheService() throws Exception
    {
        when(solrClient.getById("alfresco", "DIAGNOSTIC!LAST")).thenReturn(document("{\"report\":\"not an object\"}"));

        assertTrue(store.load("alfresco").isEmpty());
    }

    @Test(expected = IOException.class)
    public void aSolrFailureOnReadIsReported() throws Exception
    {
        when(solrClient.getById("alfresco", "DIAGNOSTIC!LAST")).thenThrow(new SolrServerException("down"));

        store.load("alfresco");
    }

    @Test(expected = IOException.class)
    public void aSolrFailureOnWriteIsReported() throws Exception
    {
        when(solrClient.add(eq("alfresco"), any(SolrInputDocument.class))).thenThrow(new SolrServerException("down"));

        store.save("alfresco", stored());
    }

    @Test
    public void aLocalePrefixedValueLoadsLikeTheBareJson() throws Exception
    {
        String json = written(stored());
        when(solrClient.getById("alfresco", "DIAGNOSTIC!LAST")).thenReturn(document("\u0000en\u0000" + json));

        assertEquals(Optional.of(stored()), store.load("alfresco"));
    }

    private String written(StoredDiagnostic diagnostic) throws Exception
    {
        store.save("alfresco", diagnostic);
        return written("alfresco").getFieldValue(SolrDocumentMapper.FIELD_DIAGNOSTIC).toString();
    }

    private SolrInputDocument written(String core) throws Exception
    {
        ArgumentCaptor<SolrInputDocument> captor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq(core), captor.capture());
        return captor.getValue();
    }

    private static SolrDocument document(String json)
    {
        SolrDocument document = new SolrDocument();
        document.setField(SolrDocumentMapper.FIELD_SOLR4_ID, "DIAGNOSTIC!LAST");
        document.setField(SolrDocumentMapper.FIELD_DIAGNOSTIC, json);
        return document;
    }

    private static StoredDiagnostic stored()
    {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("DB transaction count", 1200);
        report.put("Count of missing transactions from the Index", 0);
        Map<String, Object> errorNodes = new LinkedHashMap<>();
        errorNodes.put("totalErrorNodes", 1);
        errorNodes.put("pendingErrors", List.of(Map.of("dbId", 15695, "category", "UNRESOLVED_MODEL")));
        return new StoredDiagnostic("2026-09-25T15:02:11Z", "admin", "2026-09-25T15:04:40Z", report, errorNodes, List.of());
    }
}
