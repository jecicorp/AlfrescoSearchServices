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
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuditHealthBeanTest
{
    @SuppressWarnings("unchecked")
    private AuditHealthIndicator indicatorOver(List<MessageListenerContainer> containers)
    {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(containers);
        ConsumerFactory<String, AuditEventMessage> factory = mock(ConsumerFactory.class);
        return new AuditKafkaConfig().auditHealthIndicator(registry, factory, "audit", "group", 1000L);
    }

    @Test
    public void isDownWhenNoListenerContainerExists()
    {
        assertEquals(Status.DOWN, indicatorOver(List.of()).health().getStatus());
    }

    @Test
    public void isDownWhenTheListenerContainerIsNotRunning()
    {
        MessageListenerContainer stopped = mock(MessageListenerContainer.class);
        when(stopped.isRunning()).thenReturn(false);

        assertEquals(Status.DOWN, indicatorOver(List.of(stopped)).health().getStatus());
    }
}
