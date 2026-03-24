/*
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
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
package org.alfresco.indexing.config;

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
    @Bean
    public SolrClient solrClient(TrackerProperties props)
    {
        HttpClientBuilder builder = HttpClientBuilder.create();

        // Add secret header if secure comms is configured
        String secureComms = props.getRepository().getSecureComms();
        String secret = props.getRepository().getSharedSecret();
        if ("secret".equals(secureComms) && secret != null && !secret.isEmpty())
        {
            builder.addInterceptorFirst((HttpRequestInterceptor) (request, context) ->
                request.addHeader("X-Alfresco-Search-Secret", secret));
        }

        CloseableHttpClient httpClient = builder.build();

        return new HttpSolrClient.Builder(props.getSolr().getUrl())
            .withHttpClient(httpClient)
            .build();
    }
}
