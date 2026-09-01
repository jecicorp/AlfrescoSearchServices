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

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.alfresco.encryption.ssl.SSLEncryptionParameters;
import org.alfresco.httpclient.AlfrescoHttpClient;
import org.alfresco.httpclient.HttpClientFactory;
import org.alfresco.httpclient.HttpClientFactory.SecureCommsType;
import org.alfresco.indexing.server.solrj.LocalDictionaryService;
import org.alfresco.repo.dictionary.NamespaceDAO;
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
     * Builds the legacy {@link Properties} object carrying the <em>repository
     * connection</em> settings expected by
     * {@link SOLRAPIClientFactory#getSOLRAPIClient}.
     *
     * <p>This bean holds only repository-global connection keys (host, port,
     * secureComms, shared secret). All per-core tracker tuning (store, cron
     * schedules, batch sizes, commit intervals, …) is layered on top, per core,
     * by {@code TrackerBootstrap#buildTrackerProperties(String)} — which is the
     * single source of truth for those values.</p>
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

        LOGGER.info("Repository client configured for {} (secureComms={})",
                repo.getUrl(), repo.getSecureComms());

        return p;
    }

    /**
     * Creates an {@link AlfrescoHttpClient} that handles HTTP communication
     * (plain, shared-secret, or mTLS) with the Alfresco Repository.
     *
     * <p>When {@code secureComms=https}, an {@link SSLEncryptionParameters} is built from
     * the configured keystores via {@link SslParametersFactory#toAlfrescoParams} and the
     * 13-argument {@link HttpClientFactory} constructor is used (mirroring
     * {@code SOLRAPIClientFactory#getRepoClient}).  A {@link FileKeyResourceLoader} is
     * passed so the factory can load both keystore files and the password-metadata files
     * written by {@code SslParametersFactory}.</p>
     *
     * <p>For {@code none} and {@code secret} the existing setter-based path is preserved
     * unchanged; for {@code secret} the shared-secret header value is also set.</p>
     */
    @Bean
    public AlfrescoHttpClient alfrescoHttpClient(TrackerProperties props) throws Exception
    {
        TrackerProperties.RepositoryConfig repo = props.getRepository();
        URI repoUri = URI.create(repo.getUrl());
        String host = repoUri.getHost();
        int port = repoUri.getPort() != -1 ? repoUri.getPort() : 8080;
        String secureComms = repo.getSecureComms();
        SecureCommsType type = SecureCommsType.getType(secureComms);

        HttpClientFactory httpClientFactory;
        if (type == SecureCommsType.HTTPS)
        {
            // Build mTLS parameters from the configured keystores/truststores.
            // SslParametersFactory writes password-metadata files and sets keyMetaDataFileLocation
            // on each KeyStoreParameters so that AlfrescoKeyStoreImpl reads them via
            // FileKeyResourceLoader rather than JVM system properties.
            SSLEncryptionParameters sslParams = SslParametersFactory.toAlfrescoParams(repo.getSsl());
            httpClientFactory = new HttpClientFactory(SecureCommsType.HTTPS, sslParams,
                    new FileKeyResourceLoader(), null, null, null, null,
                    host, port, port, 40, 40, 120000);
        }
        else
        {
            // Plain (none) or shared-secret path: use setter-based factory.
            httpClientFactory = new HttpClientFactory();
            httpClientFactory.setSecureCommsType(secureComms);
            httpClientFactory.setHost(host);
            httpClientFactory.setPort(port);
            httpClientFactory.setMaxTotalConnections(40);
            httpClientFactory.setMaxHostConnections(40);
            httpClientFactory.setSocketTimeout(120000);
            if (type == SecureCommsType.SECRET)
            {
                httpClientFactory.setSharedSecret(repo.getSharedSecret());
            }
        }

        AlfrescoHttpClient client = httpClientFactory.getRepoClient(host, port);
        String baseUrl = repoUri.getPath().isEmpty() ? "/alfresco" : repoUri.getPath();
        client.setBaseUrl(baseUrl);
        return client;
    }

    /**
     * Creates a simple in-memory NamespaceDAO that maps well-known Alfresco
     * namespace prefixes to URIs. This allows SOLRAPIClient to resolve QNames
     * without requiring the full Alfresco dictionary (which lives in Solr).
     */
    @Bean
    public NamespaceDAO localNamespaceDAO()
    {
        return new SimpleNamespaceDAO();
    }

    /**
     * Creates a local dictionary service for property definition lookups.
     * This is used both by the SOLRAPIClient (for metadata deserialization)
     * and by the SolrDocumentMapper (for Solr field naming).
     */
    @Bean
    public LocalDictionaryService localDictionaryService()
    {
        return new LocalDictionaryService();
    }

    /**
     * Creates the {@link SOLRAPIClient} bean with a local NamespaceDAO
     * for QName prefix resolution and a DictionaryService for metadata parsing.
     */
    @Bean
    public SOLRAPIClient solrApiClient(AlfrescoHttpClient alfrescoHttpClient,
                                       NamespaceDAO localNamespaceDAO,
                                       LocalDictionaryService localDictionaryService)
    {
        return new SOLRAPIClient(alfrescoHttpClient,
                localDictionaryService.getDictionaryComponent(), localNamespaceDAO);
    }

    /**
     * Minimal NamespaceDAO backed by well-known Alfresco namespace mappings.
     * Populated from the standard Alfresco prefix→URI map.
     */
    static class SimpleNamespaceDAO implements NamespaceDAO
    {
        private final Map<String, String> prefixToUri = new HashMap<>();
        private final Map<String, String> uriToPrefix = new HashMap<>();

        SimpleNamespaceDAO()
        {
            // Core Alfresco namespaces
            register("d", "http://www.alfresco.org/model/dictionary/1.0");
            register("sys", "http://www.alfresco.org/model/system/1.0");
            register("cm", "http://www.alfresco.org/model/content/1.0");
            register("app", "http://www.alfresco.org/model/application/1.0");
            register("bpm", "http://www.alfresco.org/model/bpm/1.0");
            register("wf", "http://www.alfresco.org/model/workflow/1.0");
            register("fm", "http://www.alfresco.org/model/forum/1.0");
            register("ver", "http://www.alfresco.org/model/versionstore/1.0");
            register("ver2", "http://www.alfresco.org/model/versionstore/2.0");
            register("act", "http://www.alfresco.org/model/action/1.0");
            register("rule", "http://www.alfresco.org/model/rule/1.0");
            register("usr", "http://www.alfresco.org/model/user/1.0");
            register("st", "http://www.alfresco.org/model/site/1.0");
            register("imap", "http://www.alfresco.org/model/imap/1.0");
            register("dl", "http://www.alfresco.org/model/datalist/1.0");
            register("lnk", "http://www.alfresco.org/model/linksmodel/1.0");
            register("ia", "http://www.alfresco.org/model/calendar");
            register("smf", "http://www.alfresco.org/model/smart/1.0");
            register("cmis", "http://www.alfresco.org/model/cmis/1.0/cs01");
            register("cmiscustom", "http://www.alfresco.org/model/cmis/1.0/cs01ext");
            register("srft", "http://www.alfresco.org/model/solrfacetcustomproperty/1.0");
            register("trx", "http://www.alfresco.org/model/transfer/1.0");
            register("surf", "http://www.alfresco.org/model/surf/1.0");
            register("pub", "http://www.alfresco.org/model/publishing/1.0");
            register("qshare", "http://www.alfresco.org/model/qshare/1.0");
            register("download", "http://www.alfresco.org/model/download/1.0");
            register("emailserver", "http://www.alfresco.org/model/emailserver/1.0");
            register("aos", "http://www.alfresco.org/model/aos/1.0");
            register("dp", "http://www.alfresco.org/model/distributionpolicies/1.0");
            register("cmm", "http://www.alfresco.org/model/custommodelmanagement/1.0");
            register("blg", "http://www.alfresco.org/model/blogintegration/1.0");
            register("iptcxmp", "http://www.alfresco.org/model/exif/1.0");
            register("rc", "http://www.alfresco.org/model/remotecredentials/1.0");
            register("gd2", "http://www.alfresco.org/model/googledocs/2.0");
            register("custom", "http://www.alfresco.org/model/custom");
            // Publishing providers
            register("youtube", "http://www.alfresco.org/model/publishing/youtube");
            register("flickr", "http://www.alfresco.org/model/publishing/flickr");
            register("slideshare", "http://www.alfresco.org/model/publishing/slideshare");
            register("facebook", "http://www.alfresco.org/model/publishing/facebook");
            register("linkedin", "http://www.alfresco.org/model/publishing/linkedin");
            register("twitter", "http://www.alfresco.org/model/publishing/twitter");
            // Workflow
            register("inwf", "http://www.alfresco.org/model/workflow/invite/nominated/1.0");
            register("imwf", "http://www.alfresco.org/model/workflow/invite/moderated/1.0");
            register("resetpasswordwf", "http://www.alfresco.org/model/workflow/resetpassword/1.0");
        }

        private void register(String prefix, String uri)
        {
            prefixToUri.put(prefix, uri);
            uriToPrefix.put(uri, prefix);
        }

        /** Dynamically register a new prefix mapping (called when models are loaded). */
        public void registerNamespace(String prefix, String uri)
        {
            register(prefix, uri);
        }

        @Override public void addURI(String uri) { }
        @Override public void addPrefix(String prefix, String uri) { register(prefix, uri); }
        @Override public void removeURI(String uri) { uriToPrefix.remove(uri); }
        @Override public void removePrefix(String prefix) { prefixToUri.remove(prefix); }

        @Override
        public Collection<String> getURIs() { return Collections.unmodifiableCollection(prefixToUri.values()); }

        @Override
        public Collection<String> getPrefixes() { return Collections.unmodifiableCollection(prefixToUri.keySet()); }

        @Override
        public Collection<String> getPrefixes(String namespaceURI)
        {
            String prefix = uriToPrefix.get(namespaceURI);
            return prefix != null ? Collections.singleton(prefix) : Collections.emptySet();
        }

        @Override
        public String getNamespaceURI(String prefix)
        {
            return prefixToUri.get(prefix);
        }
    }
}
