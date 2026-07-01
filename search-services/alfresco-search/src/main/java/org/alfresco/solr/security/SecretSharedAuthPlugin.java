/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2022 Alfresco Software Limited
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

package org.alfresco.solr.security;

import static org.alfresco.solr.security.SecretSharedPropertyCollector.SECURE_COMMS_PROPERTY;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.solr.security.AuthenticationPlugin;

/**
 * SOLR Authentication Plugin based in shared secret token via request header.
 *
 * This Web Filter is loaded from SOLR_HOME/security.json file, so it will be executed
 * for every request to SOLR. Authentication logic is only applied when Alfresco Communication
 * is using "secret" method but it doesn't apply to "none" and "https" methods.
 *
 */
public class SecretSharedAuthPlugin extends AuthenticationPlugin
{

    private static final String SECURE_COMMS_NONE = "none";

    /**
     * Secure comms mode value for mutual TLS (mTLS).
     * When this mode is active, every request must carry a validated client certificate.
     */
    static final String SECURE_COMMS_HTTPS = "https";

    /**
     * Servlet request attribute set by the container (Jetty) when the TLS handshake
     * provided a peer certificate chain.
     */
    private static final String X509_CERT_ATTRIBUTE = "javax.servlet.request.X509Certificate";

    /**
     * Verify that request header includes "secret" word when using "secret" communication method.
     * "alfresco.secureComms.secret" value is expected as Java environment variable.
     *
     * <p>When the mode is "https", the request MUST carry a validated client certificate
     * (defense-in-depth: with Jetty {@code clientAuth=need} the TLS handshake already
     * enforces this, but this branch closes the gap for deployments that use
     * {@code clientAuth=want} and makes the intent explicit — see spec §3.1).</p>
     */
    @Override
    public boolean doAuthenticate(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws Exception
    {

        if (SecretSharedPropertyCollector.isCommsSecretShared())
        {

            HttpServletRequest httpRequest = (HttpServletRequest) request;

            if (Objects.equals(httpRequest.getHeader(SecretSharedPropertyCollector.getSecretHeader()),
                        SecretSharedPropertyCollector.getSecret()))
            {
                chain.doFilter(request, response);
                return true;
            }

            String errorMessage = "Authentication failure: \"" + SecretSharedPropertyCollector.SECRET_SHARED_METHOD_KEY
                + "\" method has been selected, use the right request header with the secret word";
            setErrorResponse(response, errorMessage);
            return false;
        }
        else if (SECURE_COMMS_NONE.equals(SecretSharedPropertyCollector.getCommsMethod())
            && !SecretSharedPropertyCollector.isAllowUnauthenticatedSolrEndpoint())
        {
            String errorMessage = "Authentication failure: \"" + SECURE_COMMS_PROPERTY
                + "=none\" is no longer supported. Please use \"https\" or \"secret\" instead.";
            setErrorResponse(response, errorMessage);
            return false;
        }
        else if (SECURE_COMMS_HTTPS.equals(SecretSharedPropertyCollector.getCommsMethod()))
        {
            // Defense-in-depth: require a validated client certificate for mTLS mode.
            // With clientAuth=need the handshake already rejects uncertified connections,
            // but this check makes the policy explicit and handles clientAuth=want deployments.
            X509Certificate[] certs = (X509Certificate[]) request.getAttribute(X509_CERT_ATTRIBUTE);
            if (certs != null && certs.length > 0)
            {
                chain.doFilter(request, response);
                return true;
            }
            String errorMessage = "Authentication failure: \"" + SECURE_COMMS_HTTPS
                + "\" mode requires a valid client certificate";
            setErrorResponse(response, errorMessage);
            return false;
        }

        chain.doFilter(request, response);
        return true;

    }

    private void setErrorResponse(ServletResponse response, String errorMessage) throws IOException
    {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, errorMessage);
    }

    @Override
    public void init(Map<String, Object> parameters)
    {
    }

    @Override
    public void close() throws IOException
    {
    }

}
