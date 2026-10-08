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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.solr.client.solrj.SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Indexes the audit topic into the audit Solr core. Makes no call to the Alfresco
 * repository, so the topic can be replayed from any offset.
 */
@Component
@ConditionalOnProperty(prefix = "alfresco.tracker.audit", name = "enabled", havingValue = "true")
public class AuditIndexer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditIndexer.class);

    private final SolrClient solrClient;
    private final AuditDocumentMapper mapper;
    private final String core;

    public AuditIndexer(SolrClient solrClient, AuditDocumentMapper mapper,
            @Value("${alfresco.tracker.audit.core:audit}") String core)
    {
        this.solrClient = solrClient;
        this.mapper = mapper;
        this.core = core;
    }

    /**
     * @param record one audit event read from the topic
     * @throws AuditException when Solr rejects the write, so the offset is not committed
     */
    @KafkaListener(topics = "${alfresco.tracker.audit.topic}",
            groupId = "${alfresco.tracker.audit.group-id}",
            containerFactory = "auditListenerContainerFactory")
    public void consume(ConsumerRecord<String, AuditEventMessage> record)
    {
        AuditEventMessage message = record.value();
        if (message == null || message.getId() == null)
        {
            LOGGER.warn("Skipping audit record with no usable payload at {}-{}", record.partition(), record.offset());
            return;
        }

        try
        {
            solrClient.add(core, mapper.toDocument(message, record.partition(), record.offset()));
        }
        catch (Exception e)
        {
            throw new AuditException("Could not index audit event " + message.getId(), e);
        }
    }
}
