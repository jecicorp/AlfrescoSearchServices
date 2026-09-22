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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private boolean recordUnindexedNodes = true;
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
    private TuningConfig tuning = new TuningConfig();
    private Map<String, CoreConfig> cores = new LinkedHashMap<>();
    private HealthConfig health = new HealthConfig();
    private BackupConfig backup = new BackupConfig();
    private ProgressConfig progress = new ProgressConfig();

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

    public boolean isRecordUnindexedNodes()
    {
        return recordUnindexedNodes;
    }

    public void setRecordUnindexedNodes(boolean recordUnindexedNodes)
    {
        this.recordUnindexedNodes = recordUnindexedNodes;
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

    public TuningConfig getTuning()
    {
        return tuning;
    }

    public void setTuning(TuningConfig tuning)
    {
        this.tuning = tuning;
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

    public ProgressConfig getProgress()
    {
        return progress;
    }

    public void setProgress(ProgressConfig progress)
    {
        this.progress = progress;
    }

    public BackupConfig getBackup()
    {
        return backup;
    }

    public void setBackup(BackupConfig backup)
    {
        this.backup = backup;
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
        r.recordUnindexedNodes = o.getRecordUnindexedNodes() != null ? o.getRecordUnindexedNodes() : recordUnindexedNodes;
        r.commitInterval = o.getCommitInterval() != null ? o.getCommitInterval() : commitInterval;
        r.newSearcherInterval = o.getNewSearcherInterval() != null ? o.getNewSearcherInterval() : newSearcherInterval;
        r.cronMetadata = oc.getMetadata() != null ? oc.getMetadata() : cron.getMetadata();
        r.cronAcl = oc.getAcl() != null ? oc.getAcl() : cron.getAcl();
        r.cronContent = oc.getContent() != null ? oc.getContent() : cron.getContent();
        r.cronCommit = oc.getCommit() != null ? oc.getCommit() : cron.getCommit();
        r.cronModel = oc.getModel() != null ? oc.getModel() : cron.getModel();
        r.cronCascade = oc.getCascade() != null ? oc.getCascade() : cron.getCascade();
        r.cronRepair = oc.getRepair() != null ? oc.getRepair() : cron.getRepair();

        BackupOverride ob = o.getBackup();
        r.backupEnabled = ob.getEnabled() != null ? ob.getEnabled() : backup.isEnabled();
        r.backupCron = ob.getCron() != null ? ob.getCron() : backup.getCron();
        r.backupLocation = ob.getLocation() != null ? ob.getLocation() : backup.getLocation();
        r.backupNumberToKeep = ob.getNumberToKeep() != null ? ob.getNumberToKeep() : backup.getNumberToKeep();

        TuningOverride ot = o.getTuning();
        r.nodeBatchSize = ot.getNodeBatchSize() != null ? ot.getNodeBatchSize() : tuning.getNodeBatchSize();
        r.transactionDocsBatchSize = ot.getTransactionDocsBatchSize() != null ? ot.getTransactionDocsBatchSize() : tuning.getTransactionDocsBatchSize();
        r.maxTransactionsPerCycle = ot.getMaxTransactionsPerCycle() != null ? ot.getMaxTransactionsPerCycle() : tuning.getMaxTransactionsPerCycle();
        r.metadataParallelism = ot.getMetadataParallelism() != null ? ot.getMetadataParallelism() : tuning.getMetadataParallelism();
        r.metadataTimeStep = ot.getMetadataTimeStep() != null ? ot.getMetadataTimeStep() : tuning.getMetadataTimeStep();
        r.aclBatchSize = ot.getAclBatchSize() != null ? ot.getAclBatchSize() : tuning.getAclBatchSize();
        r.changeSetAclsBatchSize = ot.getChangeSetAclsBatchSize() != null ? ot.getChangeSetAclsBatchSize() : tuning.getChangeSetAclsBatchSize();
        r.maxAclChangeSetsPerCycle = ot.getMaxAclChangeSetsPerCycle() != null ? ot.getMaxAclChangeSetsPerCycle() : tuning.getMaxAclChangeSetsPerCycle();
        r.aclParallelism = ot.getAclParallelism() != null ? ot.getAclParallelism() : tuning.getAclParallelism();
        r.aclTimeStep = ot.getAclTimeStep() != null ? ot.getAclTimeStep() : tuning.getAclTimeStep();
        r.contentBatchSize = ot.getContentBatchSize() != null ? ot.getContentBatchSize() : tuning.getContentBatchSize();
        r.contentParallelism = ot.getContentParallelism() != null ? ot.getContentParallelism() : tuning.getContentParallelism();
        r.cascadeNodeBatchSize = ot.getCascadeNodeBatchSize() != null ? ot.getCascadeNodeBatchSize() : tuning.getCascadeNodeBatchSize();
        r.cascadeParallelism = ot.getCascadeParallelism() != null ? ot.getCascadeParallelism() : tuning.getCascadeParallelism();
        r.cascadeCommitInterval = ot.getCascadeCommitInterval() != null ? ot.getCascadeCommitInterval() : tuning.getCascadeCommitInterval();
        r.lag = ot.getLag() != null ? ot.getLag() : tuning.getLag();
        r.holeRetention = ot.getHoleRetention() != null ? ot.getHoleRetention() : tuning.getHoleRetention();
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

    /**
     * Global Solr backup settings. The backup is triggered by the trackers
     * service against Solr's ReplicationHandler. {@code location} MUST be inside
     * Solr's {@code solr.allowPaths}, and for disaster recovery it should point
     * at a dedicated volume separate from the index data directory (so a full
     * data disk or a lost data volume does not take the backup down with it).
     */
    public static class BackupConfig
    {
        private boolean enabled = false;
        private String cron = "0 0 2 1 * ?";
        private String location = "/backup/solr";
        private int numberToKeep = 2;
        /** Poll cadence while waiting for the async ReplicationHandler outcome. */
        private long pollIntervalMillis = 2000;
        /**
         * Max time to wait for the async backup/restore outcome before reporting
         * {@code inProgress}. The Solr-side operation keeps running regardless.
         */
        private long pollTimeoutSeconds = 600;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public int getNumberToKeep() { return numberToKeep; }
        public void setNumberToKeep(int numberToKeep) { this.numberToKeep = numberToKeep; }
        public long getPollIntervalMillis() { return pollIntervalMillis; }
        public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }
        public long getPollTimeoutSeconds() { return pollTimeoutSeconds; }
        public void setPollTimeoutSeconds(long pollTimeoutSeconds) { this.pollTimeoutSeconds = pollTimeoutSeconds; }
    }

    /** Per-core backup overrides; null fields inherit {@link BackupConfig}. */
    public static class BackupOverride
    {
        private Boolean enabled;
        private String cron;
        private String location;
        private Integer numberToKeep;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public Integer getNumberToKeep() { return numberToKeep; }
        public void setNumberToKeep(Integer numberToKeep) { this.numberToKeep = numberToKeep; }
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

    /**
     * Throughput tuning. Every value has a per-core override in
     * {@link CoreConfig#getTuning()}; an unset override inherits the value here.
     */
    public static class TuningConfig
    {
        private int nodeBatchSize = 50;
        private int transactionDocsBatchSize = 2000;
        private int maxTransactionsPerCycle = 2000;
        private int metadataParallelism = 8;
        private long metadataTimeStep = 3600000;
        private int aclBatchSize = 100;
        private int changeSetAclsBatchSize = 2000;
        private int maxAclChangeSetsPerCycle = 2000;
        private int aclParallelism = 8;
        private long aclTimeStep = 3600000;
        private int contentBatchSize = 2000;
        private int contentParallelism = 8;
        private int cascadeNodeBatchSize = 10;
        private int cascadeParallelism = 8;
        private int cascadeCommitInterval = 30;
        private long lag = 1000;
        private long holeRetention = 3600000;

        public int getNodeBatchSize() { return nodeBatchSize; }
        public void setNodeBatchSize(int nodeBatchSize) { this.nodeBatchSize = nodeBatchSize; }

        public int getTransactionDocsBatchSize() { return transactionDocsBatchSize; }
        public void setTransactionDocsBatchSize(int transactionDocsBatchSize) { this.transactionDocsBatchSize = transactionDocsBatchSize; }

        public int getMaxTransactionsPerCycle() { return maxTransactionsPerCycle; }
        public void setMaxTransactionsPerCycle(int maxTransactionsPerCycle) { this.maxTransactionsPerCycle = maxTransactionsPerCycle; }

        public int getMetadataParallelism() { return metadataParallelism; }
        public void setMetadataParallelism(int metadataParallelism) { this.metadataParallelism = metadataParallelism; }

        public long getMetadataTimeStep() { return metadataTimeStep; }
        public void setMetadataTimeStep(long metadataTimeStep) { this.metadataTimeStep = metadataTimeStep; }

        public int getAclBatchSize() { return aclBatchSize; }
        public void setAclBatchSize(int aclBatchSize) { this.aclBatchSize = aclBatchSize; }

        public int getChangeSetAclsBatchSize() { return changeSetAclsBatchSize; }
        public void setChangeSetAclsBatchSize(int changeSetAclsBatchSize) { this.changeSetAclsBatchSize = changeSetAclsBatchSize; }

        public int getMaxAclChangeSetsPerCycle() { return maxAclChangeSetsPerCycle; }
        public void setMaxAclChangeSetsPerCycle(int maxAclChangeSetsPerCycle) { this.maxAclChangeSetsPerCycle = maxAclChangeSetsPerCycle; }

        public int getAclParallelism() { return aclParallelism; }
        public void setAclParallelism(int aclParallelism) { this.aclParallelism = aclParallelism; }

        public long getAclTimeStep() { return aclTimeStep; }
        public void setAclTimeStep(long aclTimeStep) { this.aclTimeStep = aclTimeStep; }

        public int getContentBatchSize() { return contentBatchSize; }
        public void setContentBatchSize(int contentBatchSize) { this.contentBatchSize = contentBatchSize; }

        public int getContentParallelism() { return contentParallelism; }
        public void setContentParallelism(int contentParallelism) { this.contentParallelism = contentParallelism; }

        public int getCascadeNodeBatchSize() { return cascadeNodeBatchSize; }
        public void setCascadeNodeBatchSize(int cascadeNodeBatchSize) { this.cascadeNodeBatchSize = cascadeNodeBatchSize; }

        public int getCascadeParallelism() { return cascadeParallelism; }
        public void setCascadeParallelism(int cascadeParallelism) { this.cascadeParallelism = cascadeParallelism; }

        public int getCascadeCommitInterval() { return cascadeCommitInterval; }
        public void setCascadeCommitInterval(int cascadeCommitInterval) { this.cascadeCommitInterval = cascadeCommitInterval; }

        public long getLag() { return lag; }
        public void setLag(long lag) { this.lag = lag; }

        public long getHoleRetention() { return holeRetention; }
        public void setHoleRetention(long holeRetention) { this.holeRetention = holeRetention; }
    }

    /**
     * Per-core tuning overrides. Every field defaults to {@code null} so that an
     * unset value inherits the global one.
     */
    public static class TuningOverride
    {
        private Integer nodeBatchSize;
        private Integer transactionDocsBatchSize;
        private Integer maxTransactionsPerCycle;
        private Integer metadataParallelism;
        private Long metadataTimeStep;
        private Integer aclBatchSize;
        private Integer changeSetAclsBatchSize;
        private Integer maxAclChangeSetsPerCycle;
        private Integer aclParallelism;
        private Long aclTimeStep;
        private Integer contentBatchSize;
        private Integer contentParallelism;
        private Integer cascadeNodeBatchSize;
        private Integer cascadeParallelism;
        private Integer cascadeCommitInterval;
        private Long lag;
        private Long holeRetention;

        public Integer getNodeBatchSize() { return nodeBatchSize; }
        public void setNodeBatchSize(Integer nodeBatchSize) { this.nodeBatchSize = nodeBatchSize; }

        public Integer getTransactionDocsBatchSize() { return transactionDocsBatchSize; }
        public void setTransactionDocsBatchSize(Integer transactionDocsBatchSize) { this.transactionDocsBatchSize = transactionDocsBatchSize; }

        public Integer getMaxTransactionsPerCycle() { return maxTransactionsPerCycle; }
        public void setMaxTransactionsPerCycle(Integer maxTransactionsPerCycle) { this.maxTransactionsPerCycle = maxTransactionsPerCycle; }

        public Integer getMetadataParallelism() { return metadataParallelism; }
        public void setMetadataParallelism(Integer metadataParallelism) { this.metadataParallelism = metadataParallelism; }

        public Long getMetadataTimeStep() { return metadataTimeStep; }
        public void setMetadataTimeStep(Long metadataTimeStep) { this.metadataTimeStep = metadataTimeStep; }

        public Integer getAclBatchSize() { return aclBatchSize; }
        public void setAclBatchSize(Integer aclBatchSize) { this.aclBatchSize = aclBatchSize; }

        public Integer getChangeSetAclsBatchSize() { return changeSetAclsBatchSize; }
        public void setChangeSetAclsBatchSize(Integer changeSetAclsBatchSize) { this.changeSetAclsBatchSize = changeSetAclsBatchSize; }

        public Integer getMaxAclChangeSetsPerCycle() { return maxAclChangeSetsPerCycle; }
        public void setMaxAclChangeSetsPerCycle(Integer maxAclChangeSetsPerCycle) { this.maxAclChangeSetsPerCycle = maxAclChangeSetsPerCycle; }

        public Integer getAclParallelism() { return aclParallelism; }
        public void setAclParallelism(Integer aclParallelism) { this.aclParallelism = aclParallelism; }

        public Long getAclTimeStep() { return aclTimeStep; }
        public void setAclTimeStep(Long aclTimeStep) { this.aclTimeStep = aclTimeStep; }

        public Integer getContentBatchSize() { return contentBatchSize; }
        public void setContentBatchSize(Integer contentBatchSize) { this.contentBatchSize = contentBatchSize; }

        public Integer getContentParallelism() { return contentParallelism; }
        public void setContentParallelism(Integer contentParallelism) { this.contentParallelism = contentParallelism; }

        public Integer getCascadeNodeBatchSize() { return cascadeNodeBatchSize; }
        public void setCascadeNodeBatchSize(Integer cascadeNodeBatchSize) { this.cascadeNodeBatchSize = cascadeNodeBatchSize; }

        public Integer getCascadeParallelism() { return cascadeParallelism; }
        public void setCascadeParallelism(Integer cascadeParallelism) { this.cascadeParallelism = cascadeParallelism; }

        public Integer getCascadeCommitInterval() { return cascadeCommitInterval; }
        public void setCascadeCommitInterval(Integer cascadeCommitInterval) { this.cascadeCommitInterval = cascadeCommitInterval; }

        public Long getLag() { return lag; }
        public void setLag(Long lag) { this.lag = lag; }

        public Long getHoleRetention() { return holeRetention; }
        public void setHoleRetention(Long holeRetention) { this.holeRetention = holeRetention; }
    }

    public static class CoreConfig
    {
        private String store;
        private Integer batchCount;
        private Integer maxLiveSearchers;
        private Boolean transformContent;
        private Boolean cascadeTrackingEnabled;
        private Boolean recordUnindexedNodes;
        private Long commitInterval;
        private Long newSearcherInterval;
        private CronOverride cron = new CronOverride();
        private TuningOverride tuning = new TuningOverride();
        private BackupOverride backup = new BackupOverride();

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

        public Boolean getRecordUnindexedNodes() { return recordUnindexedNodes; }
        public void setRecordUnindexedNodes(Boolean recordUnindexedNodes) { this.recordUnindexedNodes = recordUnindexedNodes; }

        public Long getCommitInterval() { return commitInterval; }
        public void setCommitInterval(Long commitInterval) { this.commitInterval = commitInterval; }

        public Long getNewSearcherInterval() { return newSearcherInterval; }
        public void setNewSearcherInterval(Long newSearcherInterval) { this.newSearcherInterval = newSearcherInterval; }

        public TuningOverride getTuning() { return tuning; }
        public void setTuning(TuningOverride tuning) { this.tuning = tuning; }

        public CronOverride getCron() { return cron; }
        public void setCron(CronOverride cron) { this.cron = cron; }

        public BackupOverride getBackup() { return backup; }
        public void setBackup(BackupOverride backup) { this.backup = backup; }
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
        private boolean recordUnindexedNodes;
        private long commitInterval;
        private long newSearcherInterval;
        private String cronMetadata;
        private String cronAcl;
        private String cronContent;
        private String cronCommit;
        private String cronModel;
        private String cronCascade;
        private String cronRepair;
        private boolean backupEnabled;
        private String backupCron;
        private String backupLocation;
        private int backupNumberToKeep;
        private int nodeBatchSize;
        private int transactionDocsBatchSize;
        private int maxTransactionsPerCycle;
        private int metadataParallelism;
        private long metadataTimeStep;
        private int aclBatchSize;
        private int changeSetAclsBatchSize;
        private int maxAclChangeSetsPerCycle;
        private int aclParallelism;
        private long aclTimeStep;
        private int contentBatchSize;
        private int contentParallelism;
        private int cascadeNodeBatchSize;
        private int cascadeParallelism;
        private int cascadeCommitInterval;
        private long lag;
        private long holeRetention;

        public String getStore() { return store; }
        public int getBatchCount() { return batchCount; }
        public int getMaxLiveSearchers() { return maxLiveSearchers; }
        public boolean isTransformContent() { return transformContent; }
        public boolean isCascadeTrackingEnabled() { return cascadeTrackingEnabled; }
        public boolean isRecordUnindexedNodes() { return recordUnindexedNodes; }
        public long getCommitInterval() { return commitInterval; }
        public long getNewSearcherInterval() { return newSearcherInterval; }
        public String getCronMetadata() { return cronMetadata; }
        public String getCronAcl() { return cronAcl; }
        public String getCronContent() { return cronContent; }
        public String getCronCommit() { return cronCommit; }
        public String getCronModel() { return cronModel; }
        public String getCronCascade() { return cronCascade; }
        public String getCronRepair() { return cronRepair; }
        public boolean isBackupEnabled() { return backupEnabled; }
        public String getBackupCron() { return backupCron; }
        public String getBackupLocation() { return backupLocation; }
        public int getBackupNumberToKeep() { return backupNumberToKeep; }
        public int getNodeBatchSize() { return nodeBatchSize; }
        public int getTransactionDocsBatchSize() { return transactionDocsBatchSize; }
        public int getMaxTransactionsPerCycle() { return maxTransactionsPerCycle; }
        public int getMetadataParallelism() { return metadataParallelism; }
        public long getMetadataTimeStep() { return metadataTimeStep; }
        public int getAclBatchSize() { return aclBatchSize; }
        public int getChangeSetAclsBatchSize() { return changeSetAclsBatchSize; }
        public int getMaxAclChangeSetsPerCycle() { return maxAclChangeSetsPerCycle; }
        public int getAclParallelism() { return aclParallelism; }
        public long getAclTimeStep() { return aclTimeStep; }
        public int getContentBatchSize() { return contentBatchSize; }
        public int getContentParallelism() { return contentParallelism; }
        public int getCascadeNodeBatchSize() { return cascadeNodeBatchSize; }
        public int getCascadeParallelism() { return cascadeParallelism; }
        public int getCascadeCommitInterval() { return cascadeCommitInterval; }
        public long getLag() { return lag; }
        public long getHoleRetention() { return holeRetention; }
    }

    /**
     * Settings of the indexing progress endpoints and of their sampling.
     */
    public static class ProgressConfig
    {
        private long sampleIntervalMillis = 2000;
        private long windowSeconds = 60;
        private long streamTimeoutMillis = 0;
        private List<String> corsAllowedOrigins = new ArrayList<>();

        public long getSampleIntervalMillis() { return sampleIntervalMillis; }
        public void setSampleIntervalMillis(long sampleIntervalMillis) { this.sampleIntervalMillis = sampleIntervalMillis; }
        public long getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }
        public long getStreamTimeoutMillis() { return streamTimeoutMillis; }
        public void setStreamTimeoutMillis(long streamTimeoutMillis) { this.streamTimeoutMillis = streamTimeoutMillis; }
        public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    }
}
