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

package org.alfresco.indexing.api;

import org.alfresco.indexing.admin.AdminService;
import org.alfresco.indexing.await.AwaitService;
import org.alfresco.indexing.backup.BackupService;
import org.alfresco.indexing.config.RepairReportEndpoint;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.diagnostic.DiagnosticJobService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares what {@code GET /api/v1} advertises.
 *
 * <p>Each bean takes the service that implements its capability as a parameter, so a
 * capability whose implementation is removed stops compiling here rather than being
 * advertised to clients over an endpoint that no longer serves it.
 */
@Configuration
public class ApiCapabilities
{
    @Bean
    Capability indexStatusCapability(IndexStatusService indexStatusService)
    {
        return Capability.of("index.status", "1.1");
    }

    @Bean
    Capability adminActionsCapability(AdminService adminService)
    {
        return Capability.of("admin.actions", "1.0");
    }

    @Bean
    Capability indexProgressCapability(org.alfresco.indexing.progress.ProgressService progressService)
    {
        return Capability.of("index.progress", "1.0");
    }

    @Bean
    Capability repairCapability(RepairReportEndpoint repairReportEndpoint)
    {
        return Capability.of("tracker.repair", "1.0");
    }

    @Bean
    Capability backupCapability(BackupService backupService, TrackerProperties properties)
    {
        return new Capability("admin.backup", "1.0", properties.getBackup().isEnabled());
    }

    @Bean
    Capability unindexedNodesCapability(TrackerProperties properties)
    {
        return new Capability("index.unindexed-nodes", "1.1",
                properties.isRecordUnindexedNodes());
    }

    @Bean
    Capability indexDiagnosticCapability(DiagnosticJobService diagnosticJobService)
    {
        return Capability.of("index.diagnostic", "1.1.1");
    }

    @Bean
    Capability indexAwaitCapability(ObjectProvider<AwaitService> awaitService)
    {
        return new Capability("index.await", "1.1.2", awaitService.getIfAvailable() != null);
    }
}
