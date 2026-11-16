/*-
 * #%L
 * Pristy Indexing Trackers
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
package org.alfresco.indexing.audit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies the audit retention to the index. The matching Kafka topic retention is
 * declared, not enforced: the two are aligned by configuration and logged at startup.
 */
@Component
@ConditionalOnProperty(prefix = "alfresco.tracker.audit", name = "enabled", havingValue = "true")
public class AuditPurgeScheduler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditPurgeScheduler.class);

    private final SolrClient solrClient;
    private final String core;
    private final int retentionDays;

    /**
     * @param solrClient    the client of the Solr instance holding the audit core
     * @param core          the name of the audit core
     * @param retentionDays how many days of events are kept; zero or less disables the purge
     */
    public AuditPurgeScheduler(SolrClient solrClient,
            @Value("${alfresco.tracker.audit.core:audit}") String core,
            @Value("${alfresco.tracker.audit.retention-days:1825}") int retentionDays)
    {
        this.solrClient = solrClient;
        this.core = core;
        this.retentionDays = retentionDays;
        if (retentionDays <= 0)
        {
            LOGGER.info("Audit purge disabled on core '{}': retention is {} days, so nothing is "
                    + "ever deleted.", core, retentionDays);
        }
        else
        {
            LOGGER.info("Audit retention: {} days on core '{}'. The Kafka topic retention must "
                    + "match; nothing here enforces that.", retentionDays, core);
        }
    }

    /** @return the delete query this scheduler would issue */
    public String purgeQuery()
    {
        return "EVENT_TIME:[* TO NOW-" + retentionDays + "DAYS]";
    }

    /** Deletes the events older than the retention; a failure is logged, never raised. */
    @Scheduled(cron = "${alfresco.tracker.audit.purge-cron:0 30 3 * * ?}")
    public void purge()
    {
        if (retentionDays <= 0)
        {
            return;
        }
        try
        {
            SolrQuery matching = new SolrQuery(purgeQuery());
            matching.set("defType", "lucene");
            matching.setRows(0);
            long expired = solrClient.query(core, matching).getResults().getNumFound();

            if (expired == 0)
            {
                LOGGER.debug("Audit purge on core '{}': nothing older than {} days", core, retentionDays);
                return;
            }

            solrClient.deleteByQuery(core, purgeQuery());
            solrClient.commit(core);
            LOGGER.info("Audit purge on core '{}': deleted {} events older than {} days",
                    core, expired, retentionDays);
        }
        catch (Exception e)
        {
            LOGGER.error("Audit purge failed on core '{}'", core, e);
        }
    }
}
