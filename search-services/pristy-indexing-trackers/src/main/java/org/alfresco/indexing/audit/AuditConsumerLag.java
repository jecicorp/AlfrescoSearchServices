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
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The audit consumer group's committed lag: how many events the topic holds that the
 * group has not committed yet.
 */
public class AuditConsumerLag implements Supplier<Long>
{
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ConsumerFactory<String, AuditEventMessage> consumerFactory;
    private final String topic;
    private final String groupId;

    public AuditConsumerLag(ConsumerFactory<String, AuditEventMessage> consumerFactory,
            String topic, String groupId)
    {
        this.consumerFactory = consumerFactory;
        this.topic = topic;
        this.groupId = groupId;
    }

    /** @return the number of events published but not yet committed by the group */
    @Override
    public Long get()
    {
        try (Consumer<String, AuditEventMessage> consumer = consumerFactory.createConsumer(groupId, "-lag"))
        {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, TIMEOUT);
            if (partitions == null || partitions.isEmpty())
            {
                throw new IllegalStateException("topic " + topic + " has no partition");
            }

            Set<TopicPartition> assigned = partitions.stream()
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .collect(Collectors.toSet());

            Map<TopicPartition, Long> ends = consumer.endOffsets(assigned, TIMEOUT);
            Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(assigned, TIMEOUT);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assigned, TIMEOUT);

            long lag = 0;
            for (TopicPartition partition : assigned)
            {
                Long end = ends.get(partition);
                if (end == null)
                {
                    throw new IllegalStateException("no end offset for " + partition);
                }
                OffsetAndMetadata offset = committed.get(partition);
                long from = offset != null ? offset.offset() : beginnings.getOrDefault(partition, 0L);
                lag += Math.max(0L, end - from);
            }
            return lag;
        }
    }
}
