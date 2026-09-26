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

package org.alfresco.indexing.diagnostic;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executors;

import org.alfresco.indexing.admin.AdminService;
import org.alfresco.indexing.config.RepairReportEndpoint;
import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.apache.solr.client.solrj.SolrClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wires the index diagnostic job, its store and its stream, and schedules the stream's flush and keepalive.
 */
@Configuration
public class DiagnosticConfiguration implements SchedulingConfigurer
{
    private final TrackerProperties properties;
    private final ObjectProvider<DiagnosticBroadcaster> broadcaster;

    public DiagnosticConfiguration(TrackerProperties properties, ObjectProvider<DiagnosticBroadcaster> broadcaster)
    {
        this.properties = properties;
        this.broadcaster = broadcaster;
    }

    @Bean
    DiagnosticStore diagnosticStore(SolrClient solrClient, ObjectMapper objectMapper)
    {
        return new DiagnosticStore(solrClient, objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    DiagnosticJobService diagnosticJobService(TrackerBootstrap bootstrap, AdminService adminService,
            RepairReportEndpoint repairReportEndpoint, DiagnosticStore store)
    {
        return new DiagnosticJobService(
                () -> coreNames(bootstrap, properties),
                (core, listeners) -> adminService.reportCore(core, null, null, listeners),
                repairReportEndpoint::repairReport,
                store,
                Clock.systemUTC(),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "index-diagnostic");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    @Bean
    DiagnosticBroadcaster diagnosticBroadcaster(DiagnosticJobService service)
    {
        DiagnosticBroadcaster created = new DiagnosticBroadcaster(service::snapshot);
        service.addListener(created::publish);
        return created;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar)
    {
        TrackerProperties.DiagnosticConfig config = properties.getDiagnostic();
        registrar.addFixedDelayTask(() -> broadcaster.getObject().flush(),
                Duration.ofMillis(config.getMinEventIntervalMillis()));
        registrar.addFixedDelayTask(() -> broadcaster.getObject().keepalive(),
                Duration.ofMillis(config.getKeepaliveMillis()));
    }

    /**
     * @return the cores to diagnose, sorted by name: the registered ones once the trackers have started,
     *         else the configured collections
     */
    static List<String> coreNames(TrackerBootstrap bootstrap, TrackerProperties properties)
    {
        TrackerRegistry registry = bootstrap.getRegistry();
        if (registry == null)
        {
            return List.copyOf(properties.getSolr().getCollections());
        }
        return List.copyOf(new TreeSet<>(registry.getCoreNames()));
    }
}
