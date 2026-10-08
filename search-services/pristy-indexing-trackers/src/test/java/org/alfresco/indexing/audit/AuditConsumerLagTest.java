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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AuditConsumerLagTest
{
    private static final String TOPIC = "audit";
    private static final String GROUP = "audit-indexer";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);
    private static final Set<TopicPartition> BOTH = Set.of(P0, P1);

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
        when(consumer.partitionsFor(TOPIC, AuditConsumerLag.TIMEOUT)).thenReturn(List.of(
                new PartitionInfo(TOPIC, 0, null, null, null),
                new PartitionInfo(TOPIC, 1, null, null, null)));
    }

    private void offsets(Map<TopicPartition, Long> ends, Map<TopicPartition, Long> beginnings,
            Map<TopicPartition, OffsetAndMetadata> committed)
    {
        when(consumer.endOffsets(BOTH, AuditConsumerLag.TIMEOUT)).thenReturn(ends);
        when(consumer.beginningOffsets(BOTH, AuditConsumerLag.TIMEOUT)).thenReturn(beginnings);
        when(consumer.committed(BOTH, AuditConsumerLag.TIMEOUT)).thenReturn(committed);
    }

    private long lag()
    {
        return new AuditConsumerLag(factory, TOPIC, GROUP).get();
    }

    @Test
    public void sumsTheLagOfEveryPartition()
    {
        twoPartitions();
        offsets(Map.of(P0, 100L, P1, 50L), Map.of(P0, 0L, P1, 0L),
                Map.of(P0, new OffsetAndMetadata(90L), P1, new OffsetAndMetadata(20L)));

        assertEquals(40L, lag());
    }

    @Test
    public void countsAnUncommittedPartitionFromItsBeginningOffset()
    {
        twoPartitions();
        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        committed.put(P0, new OffsetAndMetadata(90L));
        committed.put(P1, null);
        offsets(Map.of(P0, 100L, P1, 50L), Map.of(P0, 0L, P1, 30L), committed);

        assertEquals(30L, lag());
    }

    @Test
    public void clampsAPartitionWhoseCommittedOffsetExceedsItsEnd()
    {
        twoPartitions();
        offsets(Map.of(P0, 10L, P1, 50L), Map.of(P0, 0L, P1, 0L),
                Map.of(P0, new OffsetAndMetadata(90L), P1, new OffsetAndMetadata(20L)));

        assertEquals(30L, lag());
    }

    @Test(expected = IllegalStateException.class)
    public void raisesWhenAPartitionHasNoEndOffset()
    {
        twoPartitions();
        offsets(Map.of(P1, 50L), Map.of(P0, 0L, P1, 0L),
                Map.of(P0, new OffsetAndMetadata(90L), P1, new OffsetAndMetadata(20L)));

        lag();
    }

    @Test(expected = IllegalStateException.class)
    public void raisesWhenTheBrokerDoesNotKnowTheTopic()
    {
        when(consumer.partitionsFor(TOPIC, AuditConsumerLag.TIMEOUT)).thenReturn(null);

        try
        {
            lag();
        }
        finally
        {
            verify(consumer).close();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void raisesWhenTheTopicHasNoPartition()
    {
        when(consumer.partitionsFor(TOPIC, AuditConsumerLag.TIMEOUT)).thenReturn(List.of());

        try
        {
            lag();
        }
        finally
        {
            verify(consumer).close();
        }
    }

    @Test
    public void onlyIssuesMetadataAndOffsetRequestsThenClosesTheConsumer()
    {
        twoPartitions();
        offsets(Map.of(P0, 1L, P1, 1L), Map.of(P0, 0L, P1, 0L), Map.of());

        lag();

        verify(consumer).partitionsFor(TOPIC, AuditConsumerLag.TIMEOUT);
        verify(consumer).endOffsets(BOTH, AuditConsumerLag.TIMEOUT);
        verify(consumer).beginningOffsets(BOTH, AuditConsumerLag.TIMEOUT);
        verify(consumer).committed(BOTH, AuditConsumerLag.TIMEOUT);
        verify(consumer).close();
        verifyNoMoreInteractions(consumer);
    }
}
