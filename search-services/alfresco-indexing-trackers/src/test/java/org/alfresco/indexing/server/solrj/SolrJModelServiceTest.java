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
package org.alfresco.indexing.server.solrj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AlfrescoModel;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SolrJModelServiceTest
{
    private static final String COLLECTION = "alfresco";

    /** Minimal valid M2Model XML for test fixtures. */
    private static final String MODEL_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<model name=\"test:testModel\" xmlns=\"http://www.alfresco.org/model/dictionary/1.0\">\n"
            + "  <description>Test model</description>\n"
            + "  <imports>\n"
            + "    <import uri=\"http://www.alfresco.org/model/dictionary/1.0\" prefix=\"d\"/>\n"
            + "  </imports>\n"
            + "  <namespaces>\n"
            + "    <namespace uri=\"http://www.test.org/model/1.0\" prefix=\"test\"/>\n"
            + "  </namespaces>\n"
            + "</model>\n";

    @Mock
    private SolrClient solrClient;

    private SolrJModelService modelService;

    @Before
    public void setUp()
    {
        modelService = new SolrJModelService(solrClient, COLLECTION);
    }

    // -------------------------------------------------------------------------
    // putModel
    // -------------------------------------------------------------------------

    @Test
    public void putModel_successResponse_returnsTrue() throws Exception
    {
        NamedList<Object> response = new NamedList<>();
        response.add("status", "ok");
        response.add("modelName", "test:testModel");
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenReturn(response);

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        boolean result = modelService.putModel(model);

        assertTrue("putModel should return true when server responds with status=ok", result);
    }

    @Test
    public void putModel_sendsPostToCorrectHandler() throws Exception
    {
        NamedList<Object> response = new NamedList<>();
        response.add("status", "ok");
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenReturn(response);

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        modelService.putModel(model);

        ArgumentCaptor<ContentStreamUpdateRequest> captor =
                ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
        verify(solrClient).request(captor.capture(), eq(COLLECTION));

        ContentStreamUpdateRequest captured = captor.getValue();
        assertEquals("/alfresco/models", captured.getPath());
        assertEquals("put", captured.getParams().get("action"));
    }

    @Test
    public void putModel_sendsModelXmlAsContentStream() throws Exception
    {
        NamedList<Object> response = new NamedList<>();
        response.add("status", "ok");
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenReturn(response);

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        modelService.putModel(model);

        ArgumentCaptor<ContentStreamUpdateRequest> captor =
                ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
        verify(solrClient).request(captor.capture(), eq(COLLECTION));

        ContentStreamUpdateRequest captured = captor.getValue();
        assertNotNull("ContentStreams should not be null", captured.getContentStreams());
        assertTrue("ContentStreams should contain at least one stream",
                captured.getContentStreams().iterator().hasNext());
    }

    @Test
    public void putModel_errorResponse_returnsFalse() throws Exception
    {
        NamedList<Object> response = new NamedList<>();
        response.add("status", "error");
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenReturn(response);

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        boolean result = modelService.putModel(model);

        assertFalse("putModel should return false when server responds with status!=ok", result);
    }

    @Test
    public void putModel_solrException_returnsFalse() throws Exception
    {
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenThrow(new SolrServerException("connection refused"));

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        boolean result = modelService.putModel(model);

        assertFalse("putModel should return false on SolrServerException", result);
    }

    @Test
    public void putModel_ioException_returnsFalse() throws Exception
    {
        when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
                .thenThrow(new IOException("network error"));

        M2Model model = M2Model.createModel(
                new java.io.ByteArrayInputStream(MODEL_XML.getBytes("UTF-8")));
        boolean result = modelService.putModel(model);

        assertFalse("putModel should return false on IOException", result);
    }

    // -------------------------------------------------------------------------
    // afterInitModels
    // -------------------------------------------------------------------------

    @Test
    public void afterInitModels_sendsRequestToSolr() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        when(solrClient.query(eq(COLLECTION), any(ModifiableSolrParams.class)))
                .thenReturn(queryResponse);

        modelService.afterInitModels();

        verify(solrClient).query(eq(COLLECTION), any(ModifiableSolrParams.class));
    }

    @Test
    public void afterInitModels_handlesException() throws Exception
    {
        when(solrClient.query(eq(COLLECTION), any(ModifiableSolrParams.class)))
                .thenThrow(new SolrServerException("test error"));

        // Should not throw — errors are caught and logged
        modelService.afterInitModels();
    }

    // -------------------------------------------------------------------------
    // getM2Model
    // -------------------------------------------------------------------------

    @Test
    public void getM2Model_validResponse_returnsModel() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        nl.add("modelXml", MODEL_XML);
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        QName modelQName = QName.createQName("{http://www.test.org/model/1.0}testModel");
        M2Model result = modelService.getM2Model(modelQName);

        assertNotNull("getM2Model should return a non-null model", result);
        assertEquals("test:testModel", result.getName());
    }

    @Test
    public void getM2Model_noModelXmlInResponse_returnsNull() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        // no "modelXml" entry
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        QName modelQName = QName.createQName("{http://www.test.org/model/1.0}testModel");
        M2Model result = modelService.getM2Model(modelQName);

        assertNull("getM2Model should return null when modelXml is absent", result);
    }

    @Test
    public void getM2Model_solrException_returnsNull() throws Exception
    {
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class)))
                .thenThrow(new SolrServerException("server error"));

        QName modelQName = QName.createQName("{http://www.test.org/model/1.0}testModel");
        M2Model result = modelService.getM2Model(modelQName);

        assertNull("getM2Model should return null on SolrServerException", result);
    }

    // -------------------------------------------------------------------------
    // getAlfrescoModels
    // -------------------------------------------------------------------------

    @Test
    public void getAlfrescoModels_emptyModels_returnsEmptyList() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        nl.add("models", new NamedList<>());
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        List<AlfrescoModel> result = modelService.getAlfrescoModels();

        assertNotNull(result);
        assertTrue("getAlfrescoModels should return empty list when no models", result.isEmpty());
    }

    @Test
    public void getAlfrescoModels_noModelsEntry_returnsEmptyList() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        // no "models" entry
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        List<AlfrescoModel> result = modelService.getAlfrescoModels();

        assertNotNull(result);
        assertTrue("getAlfrescoModels should return empty list when models entry is absent",
                result.isEmpty());
    }

    @Test
    public void getAlfrescoModels_solrException_returnsEmptyList() throws Exception
    {
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class)))
                .thenThrow(new SolrServerException("server error"));

        List<AlfrescoModel> result = modelService.getAlfrescoModels();

        assertNotNull(result);
        assertTrue("getAlfrescoModels should return empty list on SolrServerException",
                result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // getModelErrors
    // -------------------------------------------------------------------------

    @Test
    public void getModelErrors_withErrors_returnsParsedMap() throws Exception
    {
        NamedList<Object> errors = new NamedList<>();
        errors.add("test:testModel", Arrays.asList("Constraint violation", "Invalid property"));

        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        nl.add("errors", errors);
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        Map<String, Set<String>> result = modelService.getModelErrors();

        assertNotNull(result);
        assertTrue("errors map should contain the model entry", result.containsKey("test:testModel"));
        Set<String> modelErrors = result.get("test:testModel");
        assertEquals(2, modelErrors.size());
        assertTrue(modelErrors.contains("Constraint violation"));
        assertTrue(modelErrors.contains("Invalid property"));
    }

    @Test
    public void getModelErrors_noErrors_returnsEmptyMap() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        nl.add("errors", new NamedList<>());
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        Map<String, Set<String>> result = modelService.getModelErrors();

        assertNotNull(result);
        assertTrue("getModelErrors should return empty map when no errors", result.isEmpty());
    }

    @Test
    public void getModelErrors_noErrorsEntry_returnsEmptyMap() throws Exception
    {
        QueryResponse queryResponse = mock(QueryResponse.class);
        NamedList<Object> nl = new NamedList<>();
        nl.add("status", "ok");
        // no "errors" entry
        when(queryResponse.getResponse()).thenReturn(nl);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

        Map<String, Set<String>> result = modelService.getModelErrors();

        assertNotNull(result);
        assertTrue("getModelErrors should return empty map when errors entry is absent",
                result.isEmpty());
    }

    @Test
    public void getModelErrors_solrException_returnsEmptyMap() throws Exception
    {
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class)))
                .thenThrow(new SolrServerException("server error"));

        Map<String, Set<String>> result = modelService.getModelErrors();

        assertNotNull(result);
        assertTrue("getModelErrors should return empty map on SolrServerException", result.isEmpty());
    }
}
