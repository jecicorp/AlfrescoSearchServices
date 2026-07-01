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
package org.alfresco.indexing.config;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SolrJConfig
{
    /**
     * Builds a {@link SolrClient} for the Solr channel (L2).
     *
     * <p>Auth mode is driven by {@code alfresco.tracker.solr.secure-comms}:
     * <ul>
     *   <li>{@code "secret"} — adds an {@code X-Alfresco-Search-Secret} request header
     *       with the value of {@code alfresco.tracker.solr.shared-secret} (only when
     *       the secret is non-empty).</li>
     *   <li>{@code "https"} — builds an {@link SSLContext} via
     *       {@link SslParametersFactory#toSslContext} and sets it on the HTTP client
     *       for mTLS.</li>
     *   <li>{@code "none"} (or any other value) — no auth; plain HTTP.</li>
     * </ul>
     *
     * <p><strong>Deployment note</strong>: this bean reads the <em>Solr</em> channel
     * config ({@code alfresco.tracker.solr.*}), not the repository channel config.
     * Deployments that relied on the former coupling must now set
     * {@code alfresco.tracker.solr.secure-comms} and
     * {@code alfresco.tracker.solr.shared-secret} explicitly.
     */
    @Bean
    public SolrClient solrClient(TrackerProperties props) throws Exception
    {
        TrackerProperties.SolrConfig solr = props.getSolr();
        String secureComms = solr.getSecureComms();
        HttpClientBuilder builder = HttpClientBuilder.create();

        if ("secret".equals(secureComms))
        {
            String secret = solr.getSharedSecret();
            if (secret != null && !secret.isEmpty())
            {
                builder.addInterceptorFirst((HttpRequestInterceptor) (request, context) ->
                    request.addHeader("X-Alfresco-Search-Secret", secret));
            }
        }
        else if ("https".equals(secureComms))
        {
            SSLContext sslContext = SslParametersFactory.toSslContext(solr.getSsl());
            builder.setSSLContext(sslContext);
        }

        CloseableHttpClient httpClient = builder.build();
        return new HttpSolrClient.Builder(solr.getUrl())
            .withHttpClient(httpClient)
            .build();
    }
}
