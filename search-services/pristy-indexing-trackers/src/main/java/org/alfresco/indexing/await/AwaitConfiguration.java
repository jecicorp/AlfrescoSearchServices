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

package org.alfresco.indexing.await;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.solr.client.SOLRAPIClient;
import org.apache.solr.client.solrj.SolrClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the index await service to the trackers, the repository and Solr, with its own three threads.
 */
@Configuration
@ConditionalOnProperty(prefix = "alfresco.tracker.await", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AwaitConfiguration
{
    @Bean(destroyMethod = "shutdown")
    AwaitService awaitService(TrackerProperties properties, TrackerBootstrap bootstrap, SolrClient solrClient,
            SOLRAPIClient repositoryClient)
    {
        String core = workspaceCore(properties);
        long lag = core == null ? 0L : properties.resolvedCore(core).getLag();
        return new AwaitService(
                new AwaitService.Settings(core, lag, properties.getAwait().getMaxWaiters()),
                new QuartzCoreTrackers(bootstrap),
                new RepositoryDatabaseReader(repositoryClient, TrackerProperties.WORKSPACE_STORE),
                new SolrIndexProbe(solrClient),
                Executors.newSingleThreadExecutor(daemon("index-await")),
                Executors.newSingleThreadExecutor(daemon("index-await-stream")),
                ScheduledDelays.daemon(),
                System::currentTimeMillis);
    }

    static String workspaceCore(TrackerProperties properties)
    {
        for (String core : properties.getSolr().getCollections())
        {
            if (TrackerProperties.WORKSPACE_STORE.equals(properties.resolvedCore(core).getStore()))
            {
                return core;
            }
        }
        return null;
    }

    static ThreadFactory daemon(String name)
    {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
