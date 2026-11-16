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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the Solr document of one audit event. Pure: it reads nothing but its argument,
 * which is what makes replaying the topic independent of the repository's current state.
 */
@Component
@ConditionalOnProperty(prefix = "alfresco.tracker.audit", name = "enabled", havingValue = "true")
public class AuditDocumentMapper
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditDocumentMapper.class);

    private static final DateTimeFormatter SOLR_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the mapper serialising the event properties into PROPERTIES_JSON
     */
    public AuditDocumentMapper(ObjectMapper objectMapper)
    {
        this.objectMapper = objectMapper;
    }

    /**
     * @param message the event as it arrived on the topic
     * @param partition the Kafka partition it came from
     * @param offset its offset in that partition
     * @param recordTimestamp the Kafka record timestamp, used as the event time when the message has none
     * @return the document to index, keyed by the producer's own event id
     */
    public SolrInputDocument toDocument(AuditEventMessage message, int partition, long offset,
            long recordTimestamp)
    {
        SolrInputDocument doc = new SolrInputDocument();

        doc.setField("id", message.getId());
        put(doc, "EVENT_TYPE", message.getEventType());
        put(doc, "PRINCIPAL", message.getAuthenticatedUser());
        put(doc, "EXECUTING_USER", message.getExecutingUser());
        put(doc, "USER_FIRST_NAME", message.getFirstName());
        put(doc, "USER_LAST_NAME", message.getLastName());
        put(doc, "USER_EMAIL", message.getUserEmail());

        doc.setField("EVENT_TIME", SOLR_DATE.format(Instant.ofEpochMilli(eventTime(message, recordTimestamp))));

        put(doc, "NODE_REF", ref(message.getNodeRef()));
        put(doc, "NODE_UUID", message.getNodeRef() == null ? null : message.getNodeRef().getId());
        put(doc, "NODE_DBID", message.getNodeDbId());
        put(doc, "PARENT_REF", ref(message.getParentNodeRef()));
        putAncestors(doc, message.getAncestorNodeRefs());

        put(doc, "NODE_TYPE", message.getNodeType());
        put(doc, "NODE_TYPE_TITLE", message.getNodeTypeTitle());
        putAll(doc, "NODE_ASPECT", message.getNodeAspects());
        put(doc, "SITE_ID", message.getSiteId());
        put(doc, "PATH", message.getPath());
        put(doc, "SHORT_PATH", message.getShortPath());

        put(doc, "IS_FILE", message.getFile());
        put(doc, "MIMETYPE", message.getFileMimetype());
        put(doc, "FILE_SIZE", message.getFileSize());

        Map<String, String> properties = message.getProperties();
        if (properties != null && !properties.isEmpty())
        {
            put(doc, "NODE_NAME", properties.get("cm:name"));
            try
            {
                doc.setField("PROPERTIES_JSON", objectMapper.writeValueAsString(properties));
            }
            catch (JsonProcessingException e)
            {
                LOGGER.warn("Could not serialise properties of audit event {}", message.getId(), e);
            }
        }

        doc.setField("KAFKA_PARTITION", (long) partition);
        doc.setField("KAFKA_OFFSET", offset);

        return doc;
    }

    private static long eventTime(AuditEventMessage message, long recordTimestamp)
    {
        if (message.getTimestamp() != null)
        {
            return message.getTimestamp();
        }
        if (recordTimestamp >= 0)
        {
            return recordTimestamp;
        }
        LOGGER.warn("Audit event {} carries no timestamp and its record has none either; dating it now",
                message.getId());
        return System.currentTimeMillis();
    }

    private static String ref(AuditEventMessage.NodeRefDto nodeRef)
    {
        return nodeRef == null ? null : nodeRef.toNodeRef();
    }

    private static void put(SolrInputDocument doc, String field, Object value)
    {
        if (value != null)
        {
            doc.setField(field, value);
        }
    }

    private static void putAll(SolrInputDocument doc, String field, List<String> values)
    {
        if (values == null)
        {
            return;
        }
        values.stream()
                .filter(Objects::nonNull)
                .forEach(v -> doc.addField(field, v));
    }

    private static void putAncestors(SolrInputDocument doc, List<AuditEventMessage.NodeRefDto> ancestors)
    {
        if (ancestors == null)
        {
            return;
        }
        ancestors.stream()
                .map(AuditDocumentMapper::ref)
                .filter(Objects::nonNull)
                .forEach(v -> doc.addField("ANCESTOR", v));
    }
}
