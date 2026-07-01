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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alfresco.tracker")
public class TrackerProperties
{
    /** Convention-based default stores, used when a core has no explicit {@code store} override. */
    public static final String WORKSPACE_STORE = "workspace://SpacesStore";
    public static final String ARCHIVE_STORE = "archive://SpacesStore";

    private SolrConfig solr = new SolrConfig();
    private RepositoryConfig repository = new RepositoryConfig();
    private CronConfig cron = new CronConfig();
    private int batchCount = 5000;
    private boolean cascadeTrackingEnabled = true;
    // Upstream solrcore.properties defaults: alfresco.commitInterval=2000,
    // alfresco.newSearcherInterval=3000. Index visibility (content tracking,
    // e2e waits) depends directly on the effective commit period.
    private long commitInterval = 2000;
    private long newSearcherInterval = 3000;
    // Upstream solrcore.properties defaults: alfresco.maxLiveSearchers=2,
    // alfresco.index.transformContent=true.
    private int maxLiveSearchers = 2;
    private boolean transformContent = true;
    private String solrHome = "/opt/solr/data";
    private int repairMaxRetries = 10;

    /**
     * Per-core configuration overrides, keyed by core (collection) name.
     * Any field left unset on a {@link CoreConfig} inherits the corresponding
     * global value above. Secondary cores (e.g. {@code archive}) can thus be
     * tracked less aggressively than the primary {@code alfresco} core.
     */
    private Map<String, CoreConfig> cores = new LinkedHashMap<>();
    private HealthConfig health = new HealthConfig();

    public SolrConfig getSolr()
    {
        return solr;
    }

    public void setSolr(SolrConfig solr)
    {
        this.solr = solr;
    }

    public RepositoryConfig getRepository()
    {
        return repository;
    }

    public void setRepository(RepositoryConfig repository)
    {
        this.repository = repository;
    }

    public CronConfig getCron()
    {
        return cron;
    }

    public void setCron(CronConfig cron)
    {
        this.cron = cron;
    }

    public int getBatchCount()
    {
        return batchCount;
    }

    public void setBatchCount(int batchCount)
    {
        this.batchCount = batchCount;
    }

    public boolean isCascadeTrackingEnabled()
    {
        return cascadeTrackingEnabled;
    }

    public void setCascadeTrackingEnabled(boolean cascadeTrackingEnabled)
    {
        this.cascadeTrackingEnabled = cascadeTrackingEnabled;
    }

    public long getCommitInterval()
    {
        return commitInterval;
    }

    public void setCommitInterval(long commitInterval)
    {
        this.commitInterval = commitInterval;
    }

    public long getNewSearcherInterval()
    {
        return newSearcherInterval;
    }

    public void setNewSearcherInterval(long newSearcherInterval)
    {
        this.newSearcherInterval = newSearcherInterval;
    }

    public int getMaxLiveSearchers()
    {
        return maxLiveSearchers;
    }

    public void setMaxLiveSearchers(int maxLiveSearchers)
    {
        this.maxLiveSearchers = maxLiveSearchers;
    }

    public boolean isTransformContent()
    {
        return transformContent;
    }

    public void setTransformContent(boolean transformContent)
    {
        this.transformContent = transformContent;
    }

    public Map<String, CoreConfig> getCores()
    {
        return cores;
    }

    public void setCores(Map<String, CoreConfig> cores)
    {
        this.cores = cores;
    }

    public HealthConfig getHealth()
    {
        return health;
    }

    public void setHealth(HealthConfig health)
    {
        this.health = health;
    }

    /**
     * Default store reference for a core when no explicit {@code store} override
     * is configured. By convention the core named {@code archive} tracks the
     * archive (trashcan) store; every other core tracks the live workspace store.
     */
    public static String defaultStoreFor(String coreName)
    {
        return "archive".equalsIgnoreCase(coreName) ? ARCHIVE_STORE : WORKSPACE_STORE;
    }

    /**
     * Resolves the effective configuration for a given core by layering its
     * optional per-core overrides on top of the global defaults. The returned
     * object never contains nulls and is safe to read directly.
     */
    public ResolvedCoreConfig resolvedCore(String coreName)
    {
        CoreConfig o = cores.getOrDefault(coreName, new CoreConfig());
        CronOverride oc = o.getCron();

        ResolvedCoreConfig r = new ResolvedCoreConfig();
        r.store = o.getStore() != null ? o.getStore() : defaultStoreFor(coreName);
        r.batchCount = o.getBatchCount() != null ? o.getBatchCount() : batchCount;
        r.maxLiveSearchers = o.getMaxLiveSearchers() != null ? o.getMaxLiveSearchers() : maxLiveSearchers;
        r.transformContent = o.getTransformContent() != null ? o.getTransformContent() : transformContent;
        r.cascadeTrackingEnabled = o.getCascadeTrackingEnabled() != null ? o.getCascadeTrackingEnabled() : cascadeTrackingEnabled;
        r.commitInterval = o.getCommitInterval() != null ? o.getCommitInterval() : commitInterval;
        r.newSearcherInterval = o.getNewSearcherInterval() != null ? o.getNewSearcherInterval() : newSearcherInterval;
        r.cronMetadata = oc.getMetadata() != null ? oc.getMetadata() : cron.getMetadata();
        r.cronAcl = oc.getAcl() != null ? oc.getAcl() : cron.getAcl();
        r.cronContent = oc.getContent() != null ? oc.getContent() : cron.getContent();
        r.cronCommit = oc.getCommit() != null ? oc.getCommit() : cron.getCommit();
        r.cronModel = oc.getModel() != null ? oc.getModel() : cron.getModel();
        r.cronCascade = oc.getCascade() != null ? oc.getCascade() : cron.getCascade();
        r.cronRepair = oc.getRepair() != null ? oc.getRepair() : cron.getRepair();
        return r;
    }

    public String getSolrHome()
    {
        return solrHome;
    }

    public void setSolrHome(String solrHome)
    {
        this.solrHome = solrHome;
    }

    public int getRepairMaxRetries()
    {
        return repairMaxRetries;
    }

    public void setRepairMaxRetries(int repairMaxRetries)
    {
        this.repairMaxRetries = repairMaxRetries;
    }

    public static class SslConfig
    {
        private String keyStore = "";
        private String keyStorePassword = "";
        private String keyStoreType = "PKCS12";
        private String trustStore = "";
        private String trustStorePassword = "";
        private String trustStoreType = "PKCS12";

        public String getKeyStore() { return keyStore; }
        public void setKeyStore(String keyStore) { this.keyStore = keyStore; }
        public String getKeyStorePassword() { return keyStorePassword; }
        public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
        public String getKeyStoreType() { return keyStoreType; }
        public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; }
        public String getTrustStoreType() { return trustStoreType; }
        public void setTrustStoreType(String trustStoreType) { this.trustStoreType = trustStoreType; }
    }

    public static class SolrConfig
    {
        private String url = "http://localhost:8983/solr";
        private String collection = "alfresco";
        private java.util.List<String> collections;

        public String getUrl()
        {
            return url;
        }

        public void setUrl(String url)
        {
            this.url = url;
        }

        /** Returns the first (or only) collection name. */
        public String getCollection()
        {
            return collection;
        }

        public void setCollection(String collection)
        {
            this.collection = collection;
        }

        /** Returns all collections to track. Falls back to single {@code collection} if not set. */
        public java.util.List<String> getCollections()
        {
            if (collections != null && !collections.isEmpty())
            {
                return collections;
            }
            return java.util.List.of(collection);
        }

        public void setCollections(java.util.List<String> collections)
        {
            this.collections = collections;
        }

        private String secureComms = "none"; // none, secret, https
        private String sharedSecret = "";
        private SslConfig ssl = new SslConfig();

        public String getSecureComms() { return secureComms; }
        public void setSecureComms(String secureComms) { this.secureComms = secureComms; }
        public String getSharedSecret() { return sharedSecret; }
        public void setSharedSecret(String sharedSecret) { this.sharedSecret = sharedSecret; }
        public SslConfig getSsl() { return ssl; }
        public void setSsl(SslConfig ssl) { this.ssl = ssl; }
    }

    public static class RepositoryConfig
    {
        private String url = "http://localhost:8080/alfresco";
        private String secureComms = "none"; // none, secret, https
        private String sharedSecret = "";

        public String getUrl()
        {
            return url;
        }

        public void setUrl(String url)
        {
            this.url = url;
        }

        public String getSecureComms()
        {
            return secureComms;
        }

        public void setSecureComms(String secureComms)
        {
            this.secureComms = secureComms;
        }

        public String getSharedSecret()
        {
            return sharedSecret;
        }

        public void setSharedSecret(String sharedSecret)
        {
            this.sharedSecret = sharedSecret;
        }

        private SslConfig ssl = new SslConfig();

        public SslConfig getSsl() { return ssl; }
        public void setSsl(SslConfig ssl) { this.ssl = ssl; }
    }

    /**
     * Settings for the {@code RepositoryHealthIndicator}, which probes the
     * Alfresco Repository URL exposed on the {@code /actuator/health} endpoint.
     */
    public static class HealthConfig
    {
        /** Connection timeout for the repository health probe, in milliseconds. */
        private int connectTimeout = 5000;
        /** Read timeout for the repository health probe, in milliseconds. */
        private int readTimeout = 5000;

        public int getConnectTimeout()
        {
            return connectTimeout;
        }

        public void setConnectTimeout(int connectTimeout)
        {
            this.connectTimeout = connectTimeout;
        }

        public int getReadTimeout()
        {
            return readTimeout;
        }

        public void setReadTimeout(int readTimeout)
        {
            this.readTimeout = readTimeout;
        }
    }

    public static class CronConfig
    {
        private String metadata = "0/10 * * * * ?";
        private String acl = "0/10 * * * * ?";
        private String content = "0/10 * * * * ?";
        private String commit = "0/5 * * * * ?";
        private String model = "0/10 * * * * ?";
        private String cascade = "0/10 * * * * ?";
        private String repair = "0 0/1 * * * ?";

        public String getMetadata()
        {
            return metadata;
        }

        public void setMetadata(String metadata)
        {
            this.metadata = metadata;
        }

        public String getAcl()
        {
            return acl;
        }

        public void setAcl(String acl)
        {
            this.acl = acl;
        }

        public String getContent()
        {
            return content;
        }

        public void setContent(String content)
        {
            this.content = content;
        }

        public String getCommit()
        {
            return commit;
        }

        public void setCommit(String commit)
        {
            this.commit = commit;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public String getCascade()
        {
            return cascade;
        }

        public void setCascade(String cascade)
        {
            this.cascade = cascade;
        }

        public String getRepair()
        {
            return repair;
        }

        public void setRepair(String repair)
        {
            this.repair = repair;
        }
    }

    /**
     * Per-core overrides. Every field is a nullable wrapper: {@code null} means
     * "not overridden — inherit the global default". Bound from
     * {@code alfresco.tracker.cores.<coreName>.*}.
     */
    public static class CoreConfig
    {
        private String store;
        private Integer batchCount;
        private Integer maxLiveSearchers;
        private Boolean transformContent;
        private Boolean cascadeTrackingEnabled;
        private Long commitInterval;
        private Long newSearcherInterval;
        private CronOverride cron = new CronOverride();

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }

        public Integer getBatchCount() { return batchCount; }
        public void setBatchCount(Integer batchCount) { this.batchCount = batchCount; }

        public Integer getMaxLiveSearchers() { return maxLiveSearchers; }
        public void setMaxLiveSearchers(Integer maxLiveSearchers) { this.maxLiveSearchers = maxLiveSearchers; }

        public Boolean getTransformContent() { return transformContent; }
        public void setTransformContent(Boolean transformContent) { this.transformContent = transformContent; }

        public Boolean getCascadeTrackingEnabled() { return cascadeTrackingEnabled; }
        public void setCascadeTrackingEnabled(Boolean cascadeTrackingEnabled) { this.cascadeTrackingEnabled = cascadeTrackingEnabled; }

        public Long getCommitInterval() { return commitInterval; }
        public void setCommitInterval(Long commitInterval) { this.commitInterval = commitInterval; }

        public Long getNewSearcherInterval() { return newSearcherInterval; }
        public void setNewSearcherInterval(Long newSearcherInterval) { this.newSearcherInterval = newSearcherInterval; }

        public CronOverride getCron() { return cron; }
        public void setCron(CronOverride cron) { this.cron = cron; }
    }

    /**
     * Per-core cron overrides. Unlike {@link CronConfig}, every field defaults
     * to {@code null} so that an unset schedule inherits the global one.
     */
    public static class CronOverride
    {
        private String metadata;
        private String acl;
        private String content;
        private String commit;
        private String model;
        private String cascade;
        private String repair;

        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }

        public String getAcl() { return acl; }
        public void setAcl(String acl) { this.acl = acl; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getCommit() { return commit; }
        public void setCommit(String commit) { this.commit = commit; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getCascade() { return cascade; }
        public void setCascade(String cascade) { this.cascade = cascade; }

        public String getRepair() { return repair; }
        public void setRepair(String repair) { this.repair = repair; }
    }

    /**
     * Fully-resolved, null-free effective configuration for a single core,
     * produced by {@link #resolvedCore(String)}.
     */
    public static class ResolvedCoreConfig
    {
        private String store;
        private int batchCount;
        private int maxLiveSearchers;
        private boolean transformContent;
        private boolean cascadeTrackingEnabled;
        private long commitInterval;
        private long newSearcherInterval;
        private String cronMetadata;
        private String cronAcl;
        private String cronContent;
        private String cronCommit;
        private String cronModel;
        private String cronCascade;
        private String cronRepair;

        public String getStore() { return store; }
        public int getBatchCount() { return batchCount; }
        public int getMaxLiveSearchers() { return maxLiveSearchers; }
        public boolean isTransformContent() { return transformContent; }
        public boolean isCascadeTrackingEnabled() { return cascadeTrackingEnabled; }
        public long getCommitInterval() { return commitInterval; }
        public long getNewSearcherInterval() { return newSearcherInterval; }
        public String getCronMetadata() { return cronMetadata; }
        public String getCronAcl() { return cronAcl; }
        public String getCronContent() { return cronContent; }
        public String getCronCommit() { return cronCommit; }
        public String getCronModel() { return cronModel; }
        public String getCronCascade() { return cronCascade; }
        public String getCronRepair() { return cronRepair; }
    }
}
