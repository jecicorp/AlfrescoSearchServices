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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.Before;
import org.junit.Test;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AuditConsumerLagTest
{
    private static final String TOPIC = "audit";
    private static final String GROUP = "audit-indexer";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);

    private ConsumerFactory<String, AuditEventMessage> factory;
    private Consumer<String, AuditEventMessage> consumer;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp()
    {
        factory = mock(ConsumerFactory.class);
        consumer = mock(Consumer.class);
        when(factory.createConsumer(GROUP, "-lag")).thenReturn(consumer);
    }

    private void twoPartitions()
    {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of(
                new PartitionInfo(TOPIC, 0, null, null, null),
                new PartitionInfo(TOPIC, 1, null, null, null)));
    }

    @Test
    public void sumsTheLagOfEveryPartition()
    {
        twoPartitions();
        when(consumer.endOffsets(any())).thenReturn(Map.of(P0, 100L, P1, 50L));
        when(consumer.committed(Set.of(P0, P1))).thenReturn(Map.of(
                P0, new OffsetAndMetadata(90L), P1, new OffsetAndMetadata(20L)));

        assertEquals(40L, (long) new AuditConsumerLag(factory, TOPIC, GROUP).get());
    }

    @Test
    public void countsAPartitionWithNothingCommittedWhole()
    {
        twoPartitions();
        when(consumer.endOffsets(any())).thenReturn(Map.of(P0, 100L, P1, 50L));
        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        committed.put(P0, new OffsetAndMetadata(90L));
        committed.put(P1, null);
        when(consumer.committed(Set.of(P0, P1))).thenReturn(committed);

        assertEquals(60L, (long) new AuditConsumerLag(factory, TOPIC, GROUP).get());
    }

    @Test(expected = IllegalStateException.class)
    public void raisesWhenTheBrokerDoesNotKnowTheTopic()
    {
        when(consumer.partitionsFor(TOPIC)).thenReturn(null);

        new AuditConsumerLag(factory, TOPIC, GROUP).get();
    }

    @Test(expected = IllegalStateException.class)
    public void raisesWhenTheTopicHasNoPartition()
    {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of());

        new AuditConsumerLag(factory, TOPIC, GROUP).get();
    }

    @Test
    public void neverSubscribesAndClosesTheConsumer()
    {
        twoPartitions();
        when(consumer.endOffsets(any())).thenReturn(Map.of(P0, 1L, P1, 1L));
        when(consumer.committed(anySet())).thenReturn(Map.of());

        new AuditConsumerLag(factory, TOPIC, GROUP).get();

        verify(consumer, never()).subscribe(any(java.util.Collection.class));
        verify(consumer, never()).assign(any());
        verify(consumer).close();
    }
}
