/*-
 * #%L
 * Alfresco Solr Search
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

package org.alfresco.solr.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ModelUpdateRequestHandler}.
 */
public class ModelUpdateRequestHandlerTest
{
    private ModelUpdateRequestHandler handler;
    private SolrQueryRequest req;
    private SolrQueryResponse rsp;
    private SolrParams params;

    @Before
    public void setUp()
    {
        handler = new ModelUpdateRequestHandler();
        req = mock(SolrQueryRequest.class);
        rsp = new SolrQueryResponse();
        params = mock(SolrParams.class);
        when(req.getParams()).thenReturn(params);
    }

    @Test
    public void testInstantiation()
    {
        assertNotNull(handler);
        assertNotNull(handler.getDescription());
    }

    @Test(expected = SolrException.class)
    public void testMissingActionParameter() throws Exception
    {
        when(params.get("action")).thenReturn(null);
        handler.handleRequestBody(req, rsp);
    }

    @Test(expected = SolrException.class)
    public void testEmptyActionParameter() throws Exception
    {
        when(params.get("action")).thenReturn("");
        handler.handleRequestBody(req, rsp);
    }

    @Test(expected = SolrException.class)
    public void testUnknownAction() throws Exception
    {
        when(params.get("action")).thenReturn("invalid");
        handler.handleRequestBody(req, rsp);
    }

    @Test(expected = SolrException.class)
    public void testPutWithoutContentStream() throws Exception
    {
        when(params.get("action")).thenReturn("put");
        when(req.getContentStreams()).thenReturn(null);
        handler.handleRequestBody(req, rsp);
    }

    @Test(expected = SolrException.class)
    public void testRemoveWithoutModelQName() throws Exception
    {
        when(params.get("action")).thenReturn("remove");
        when(params.get("modelQName")).thenReturn(null);
        handler.handleRequestBody(req, rsp);
    }

    @Test(expected = SolrException.class)
    public void testGetWithoutModelQName() throws Exception
    {
        when(params.get("action")).thenReturn("get");
        when(params.get("modelQName")).thenReturn(null);
        handler.handleRequestBody(req, rsp);
    }

    @Test
    public void testGetDescription()
    {
        String description = handler.getDescription();
        assertNotNull(description);
        assertEquals("Handles Alfresco M2Model updates for remote tracker synchronization", description);
    }
}
