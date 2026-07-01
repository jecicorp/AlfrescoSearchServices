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

package org.alfresco.solr.security;

import static org.alfresco.solr.security.SecretSharedPropertyCollector.PROPS_CACHE;
import static org.alfresco.solr.security.SecretSharedPropertyCollector.SECURE_COMMS_PROPERTY;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code https} defense-in-depth branch in {@link SecretSharedAuthPlugin}.
 *
 * <p>When {@code alfresco.secureComms=https}, every request must carry a validated client
 * certificate (X509Certificate[] attribute set by the container).  With Jetty
 * {@code clientAuth=need} the TLS handshake already enforces this; this check closes the
 * gap for deployments that use {@code clientAuth=want} and makes the policy explicit.</p>
 */
public class SecretSharedAuthPluginHttpsTest
{
    private SecretSharedAuthPlugin plugin;

    @Before
    public void setUp()
    {
        plugin = new SecretSharedAuthPlugin();
        plugin.init(java.util.Collections.emptyMap());
        // Force https mode via the system property used by SecretSharedPropertyCollector
        System.setProperty(SECURE_COMMS_PROPERTY, SecretSharedAuthPlugin.SECURE_COMMS_HTTPS);
        PROPS_CACHE.clear();
    }

    @After
    public void tearDown()
    {
        System.clearProperty(SECURE_COMMS_PROPERTY);
        PROPS_CACHE.clear();
    }

    /**
     * A request that carries a non-empty X509Certificate[] must be allowed through:
     * doFilter must be called and doAuthenticate must return true.
     */
    @Test
    public void httpsMode_withClientCert_shouldAllow() throws Exception
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        X509Certificate cert = mock(X509Certificate.class);
        when(request.getAttribute("javax.servlet.request.X509Certificate"))
            .thenReturn(new X509Certificate[]{ cert });

        boolean result = plugin.doAuthenticate(request, response, chain);

        assertTrue("Expected doAuthenticate to return true when client cert is present", result);
        verify(chain).doFilter(request, response);
    }

    /**
     * A request with NO client certificate must be rejected with 403:
     * doFilter must NOT be called and doAuthenticate must return false.
     */
    @Test
    public void httpsMode_withoutClientCert_shouldReject() throws Exception
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getAttribute("javax.servlet.request.X509Certificate")).thenReturn(null);

        boolean result = plugin.doAuthenticate(request, response, chain);

        assertFalse("Expected doAuthenticate to return false when no client cert is presented", result);
        verify(chain, never()).doFilter(request, response);
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
            "Authentication failure: \"https\" mode requires a valid client certificate");
    }

    /**
     * A request with an explicitly empty X509Certificate[] must also be rejected.
     */
    @Test
    public void httpsMode_withEmptyCertArray_shouldReject() throws Exception
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getAttribute("javax.servlet.request.X509Certificate"))
            .thenReturn(new X509Certificate[0]);

        boolean result = plugin.doAuthenticate(request, response, chain);

        assertFalse("Expected doAuthenticate to return false when cert array is empty", result);
        verify(chain, never()).doFilter(request, response);
    }
}
