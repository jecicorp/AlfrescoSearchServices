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
package org.alfresco.indexing.backup;

import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.config.TrackerProperties.ResolvedCoreConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Registers one cron-scheduled backup task per tracked core whose resolved
 * backup configuration is enabled. Uses the global/per-core model from
 * {@link TrackerProperties#resolvedCore(String)}.
 */
@Component
public class BackupScheduler implements SchedulingConfigurer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupScheduler.class);

    private final TrackerProperties props;
    private final BackupService backupService;

    public BackupScheduler(TrackerProperties props, BackupService backupService)
    {
        this.props = props;
        this.backupService = backupService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar)
    {
        int registered = 0;
        for (String core : props.getSolr().getCollections())
        {
            ResolvedCoreConfig cfg = props.resolvedCore(core);
            if (!cfg.isBackupEnabled())
            {
                LOGGER.info("Backup disabled for core '{}'", core);
                continue;
            }
            LOGGER.info("Scheduling backup for core '{}': cron='{}', location='{}', numberToKeep={}",
                    core, cfg.getBackupCron(), cfg.getBackupLocation(), cfg.getBackupNumberToKeep());
            registrar.addCronTask(
                    () -> backupService.backupCore(core, cfg.getBackupLocation(), cfg.getBackupNumberToKeep()),
                    cfg.getBackupCron());
            registered++;
        }
        if (registered == 0)
        {
            // The migration guide tells admins to neutralise the legacy
            // repository-driven backup jobs; without this trackers-side opt-in
            // NO index backup runs at all, and nothing else ever complains.
            LOGGER.warn("No scheduled Solr backup is registered: alfresco.tracker.backup.enabled is false "
                    + "for every core. If the legacy repository-driven backup was disabled, the index "
                    + "currently has NO scheduled backup at all -- set ALFRESCO_TRACKER_BACKUP_ENABLED=true "
                    + "(or the per-core override) to enable it.");
        }
    }
}
