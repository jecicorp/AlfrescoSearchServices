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

import org.junit.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AuditHealthIndicatorTest
{
    @Test
    public void isDownWithoutAskingKafkaWhenTheListenerContainerIsNotRunning()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> false, () -> {
            throw new AssertionError("Kafka must not be called");
        }, 1000L);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    public void isUpWhenTheLagIsUnderTheThreshold()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> true, () -> 12L, 1000L);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(12L, health.getDetails().get("lag"));
        assertEquals(1000L, health.getDetails().get("threshold"));
    }

    @Test
    public void isUpWhenTheLagEqualsTheThreshold()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> true, () -> 1000L, 1000L);

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    public void isDownWhenTheLagExceedsTheThreshold()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> true, () -> 5000L, 1000L);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    public void isDownWhenTheLagCannotBeRead()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> true, () -> {
            throw new IllegalStateException("no consumer");
        }, 1000L);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertNotNull(health.getDetails().get("reason"));
    }

    @Test
    public void isDownWhenTheFailureCarriesNoMessage()
    {
        AuditHealthIndicator indicator = new AuditHealthIndicator(() -> true, () -> {
            throw new IllegalStateException();
        }, 1000L);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertNotNull(health.getDetails().get("reason"));
    }
}
