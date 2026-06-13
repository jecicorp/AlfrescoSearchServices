/*
 * Copyright 2026 - Jeci SARL - https://jeci.fr
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * http://www.gnu.org/licenses/.
 */
package org.alfresco.indexing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alfresco.tracker")
public class TrackerProperties
{
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
    private String solrHome = "/opt/solr/data";
    private int repairMaxRetries = 10;

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
}
