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

import java.net.URI;
import java.util.Properties;

import org.alfresco.httpclient.AlfrescoHttpClient;
import org.alfresco.httpclient.HttpClientFactory;
import org.alfresco.httpclient.HttpClientFactory.SecureCommsType;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.SOLRAPIClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link SOLRAPIClient} bean used to communicate with the
 * Alfresco Repository REST API.
 *
 * <p>The creation logic mirrors what {@link SOLRAPIClientFactory} does when
 * running inside Solr, but sources its configuration from Spring Boot
 * {@link TrackerProperties} instead of {@code solrcore.properties}.</p>
 */
@Configuration
public class RepositoryClientConfig
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryClientConfig.class);

    /**
     * Builds the legacy {@link Properties} object expected by
     * {@link SOLRAPIClientFactory#getSOLRAPIClient}.
     */
    @Bean
    public Properties repositoryProperties(TrackerProperties props)
    {
        TrackerProperties.RepositoryConfig repo = props.getRepository();
        URI repoUri = URI.create(repo.getUrl());

        Properties p = new Properties();
        p.setProperty("alfresco.host", repoUri.getHost());
        p.setProperty("alfresco.port", String.valueOf(repoUri.getPort() != -1 ? repoUri.getPort() : 8080));
        p.setProperty("alfresco.port.ssl", String.valueOf(repoUri.getPort() != -1 ? repoUri.getPort() : 8443));
        p.setProperty("alfresco.baseUrl", repoUri.getPath().isEmpty() ? "/alfresco" : repoUri.getPath());
        p.setProperty("alfresco.secureComms", repo.getSecureComms());

        if ("secret".equalsIgnoreCase(repo.getSecureComms()))
        {
            p.setProperty("alfresco.secureComms.secret", repo.getSharedSecret());
        }

        // Batch / tracker properties
        p.setProperty("alfresco.batch.count", String.valueOf(props.getBatchCount()));

        // Cron schedules — keyed as TrackerScheduler expects them
        TrackerProperties.CronConfig cron = props.getCron();
        p.setProperty("alfresco.metadata.tracker.cron", cron.getMetadata());
        p.setProperty("alfresco.acl.tracker.cron", cron.getAcl());
        p.setProperty("alfresco.content.tracker.cron", cron.getContent());
        p.setProperty("alfresco.commit.tracker.cron", cron.getCommit());
        p.setProperty("alfresco.model.tracker.cron", cron.getModel());
        p.setProperty("alfresco.cascade.tracker.cron", cron.getCascade());
        p.setProperty("alfresco.cascade.tracker.enabled", String.valueOf(props.isCascadeTrackingEnabled()));

        LOGGER.info("Repository client configured for {} (secureComms={})",
                repo.getUrl(), repo.getSecureComms());

        return p;
    }

    /**
     * Creates an {@link AlfrescoHttpClient} that handles HTTP communication
     * (plain, shared-secret, or mTLS) with the Alfresco Repository.
     */
    @Bean
    public AlfrescoHttpClient alfrescoHttpClient(TrackerProperties props)
    {
        TrackerProperties.RepositoryConfig repo = props.getRepository();
        URI repoUri = URI.create(repo.getUrl());

        String host = repoUri.getHost();
        int port = repoUri.getPort() != -1 ? repoUri.getPort() : 8080;
        String secureComms = repo.getSecureComms();

        HttpClientFactory httpClientFactory = new HttpClientFactory();
        httpClientFactory.setSecureCommsType(secureComms);
        httpClientFactory.setHost(host);
        httpClientFactory.setPort(port);
        httpClientFactory.setMaxTotalConnections(40);
        httpClientFactory.setMaxHostConnections(40);
        httpClientFactory.setSocketTimeout(120000);

        if (SecureCommsType.getType(secureComms) == SecureCommsType.SECRET)
        {
            httpClientFactory.setSharedSecret(repo.getSharedSecret());
        }

        AlfrescoHttpClient client = httpClientFactory.getRepoClient(host, port);
        String baseUrl = repoUri.getPath().isEmpty() ? "/alfresco" : repoUri.getPath();
        client.setBaseUrl(baseUrl);

        return client;
    }

    /**
     * Creates the {@link SOLRAPIClient} bean. This is the main client used by
     * trackers to fetch nodes, ACLs, and metadata from the Repository.
     *
     * <p>Note: The {@code dictionaryService} and {@code namespaceDAO}
     * parameters are set to {@code null} for now. They will be wired in
     * Task 3 (TrackerBootstrap) once the data model initialisation is in place.</p>
     */
    @Bean
    public SOLRAPIClient solrApiClient(AlfrescoHttpClient alfrescoHttpClient)
    {
        // DictionaryService and NamespaceDAO will be provided by TrackerBootstrap (Task 3)
        return new SOLRAPIClient(alfrescoHttpClient, null, null);
    }
}
