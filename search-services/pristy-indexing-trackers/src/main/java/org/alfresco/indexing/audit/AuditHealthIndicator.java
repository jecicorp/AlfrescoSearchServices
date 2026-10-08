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

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reports the audit consumer's lag. A stopped indexer answers every query with stale
 * data and raises nothing on its own; the lag is what makes that visible.
 */
public class AuditHealthIndicator implements HealthIndicator
{
    private final BooleanSupplier running;
    private final Supplier<Long> lag;
    private final long threshold;

    public AuditHealthIndicator(BooleanSupplier running, Supplier<Long> lag, long threshold)
    {
        this.running = running;
        this.lag = lag;
        this.threshold = threshold;
    }

    @Override
    public Health health()
    {
        if (!running.getAsBoolean())
        {
            return Health.down().withDetail("reason", "the audit listener container is not running").build();
        }

        long current;
        try
        {
            current = lag.get();
        }
        catch (Exception e)
        {
            return Health.down()
                    .withDetail("reason", "the lag cannot be measured: " + e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }

        Health.Builder builder = current > threshold ? Health.down() : Health.up();
        return builder.withDetail("lag", current).withDetail("threshold", threshold).build();
    }
}
