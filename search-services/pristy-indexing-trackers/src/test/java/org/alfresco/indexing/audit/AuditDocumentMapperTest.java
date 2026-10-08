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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AuditDocumentMapperTest
{
    private final AuditDocumentMapper mapper = new AuditDocumentMapper(new ObjectMapper());

    private AuditEventMessage.NodeRefDto nodeRef(String uuid)
    {
        AuditEventMessage.StoreRefDto store = new AuditEventMessage.StoreRefDto();
        store.setProtocol("workspace");
        store.setIdentifier("SpacesStore");
        AuditEventMessage.NodeRefDto ref = new AuditEventMessage.NodeRefDto();
        ref.setStoreRef(store);
        ref.setId(uuid);
        return ref;
    }

    private AuditEventMessage message()
    {
        AuditEventMessage m = new AuditEventMessage();
        m.setId("5a9f8e7d-0000-4000-8000-000000000001");
        m.setTimestamp(1767225600000L);
        m.setEventType("update-properties");
        m.setAuthenticatedUser("abeecher");
        m.setExecutingUser("abeecher");
        m.setFirstName("Alice");
        m.setLastName("Beecher");
        m.setUserEmail("abeecher@example.org");
        m.setNodeRef(nodeRef("44444444-4444-4444-4444-444444444444"));
        m.setNodeDbId(4242L);
        m.setParentNodeRef(nodeRef("33333333-3333-3333-3333-333333333333"));
        m.setAncestorNodeRefs(List.of(nodeRef("22222222-2222-2222-2222-222222222222"),
                nodeRef("33333333-3333-3333-3333-333333333333")));
        m.setNodeType("cm:content");
        m.setNodeTypeTitle("Content");
        m.setNodeAspects(List.of("cm:titled", "cm:auditable"));
        m.setSiteId("marches");
        m.setPath("/Company Home/Sites/marches");
        m.setShortPath("/marches");
        m.setFile(Boolean.TRUE);
        m.setFileMimetype("application/pdf");
        m.setFileSize(10240L);
        m.setProperties(Map.of("cm:name", "piece.pdf", "cm:title", "Piece jointe"));
        return m;
    }

    @Test
    public void mapsTheIdentityAndTheJoinKey()
    {
        SolrInputDocument doc = mapper.toDocument(message(), 3, 987L, 0L);

        assertEquals("5a9f8e7d-0000-4000-8000-000000000001", doc.getFieldValue("id"));
        assertEquals(4242L, doc.getFieldValue("NODE_DBID"));
        assertEquals("workspace://SpacesStore/44444444-4444-4444-4444-444444444444",
                doc.getFieldValue("NODE_REF"));
        assertEquals("44444444-4444-4444-4444-444444444444", doc.getFieldValue("NODE_UUID"));
        assertEquals(3L, doc.getFieldValue("KAFKA_PARTITION"));
        assertEquals(987L, doc.getFieldValue("KAFKA_OFFSET"));
    }

    @Test
    public void mapsTheEventTimeAsAnInstant()
    {
        SolrInputDocument doc = mapper.toDocument(message(), 0, 0L, 1000L);

        assertEquals("2026-01-01T00:00:00Z", doc.getFieldValue("EVENT_TIME"));
    }

    @Test
    public void fallsBackToTheRecordTimestampWhenTheMessageHasNone()
    {
        AuditEventMessage m = message();
        m.setTimestamp(null);

        SolrInputDocument doc = mapper.toDocument(m, 0, 0L, 1767225600000L);

        assertEquals("2026-01-01T00:00:00Z", doc.getFieldValue("EVENT_TIME"));
    }

    @Test
    public void datesAnEventNearNowWhenNeitherTimestampIsUsable()
    {
        AuditEventMessage m = message();
        m.setTimestamp(null);
        long before = System.currentTimeMillis();

        SolrInputDocument doc = mapper.toDocument(m, 0, 0L, -1L);

        long eventTime = java.time.Instant.parse((String) doc.getFieldValue("EVENT_TIME")).toEpochMilli();
        assertTrue(eventTime >= before - 1000L);
        assertTrue(eventTime <= System.currentTimeMillis() + 1000L);
    }

    @Test
    public void takesTheNodeNameFromTheCmNameProperty()
    {
        SolrInputDocument doc = mapper.toDocument(message(), 0, 0L, 0L);

        assertEquals("piece.pdf", doc.getFieldValue("NODE_NAME"));
    }

    @Test
    public void carriesEveryAncestor()
    {
        SolrInputDocument doc = mapper.toDocument(message(), 0, 0L, 0L);

        assertEquals(2, doc.getFieldValues("ANCESTOR").size());
        assertTrue(doc.getFieldValues("ANCESTOR")
                .contains("workspace://SpacesStore/33333333-3333-3333-3333-333333333333"));
    }

    @Test
    public void serialisesThePropertiesAsJson()
    {
        SolrInputDocument doc = mapper.toDocument(message(), 0, 0L, 0L);
        String json = (String) doc.getFieldValue("PROPERTIES_JSON");

        assertTrue(json.contains("\"cm:name\""));
        assertTrue(json.contains("piece.pdf"));
    }

    @Test
    public void toleratesAMessageFromAnOlderProducer()
    {
        AuditEventMessage m = message();
        m.setNodeDbId(null);
        m.setAncestorNodeRefs(null);

        SolrInputDocument doc = mapper.toDocument(m, 0, 0L, 0L);

        assertNull(doc.getFieldValue("NODE_DBID"));
        assertNull(doc.getFieldValue("ANCESTOR"));
        assertEquals("piece.pdf", doc.getFieldValue("NODE_NAME"));
    }

    @Test
    public void deserialisesTheProducersNestedNodeRefShape() throws Exception
    {
        String json = """
                {
                  "id": "e1",
                  "timestamp": 1767225600000,
                  "eventType": "create-node",
                  "authenticatedUser": "abeecher",
                  "nodeRef": {
                    "storeRef": { "protocol": "workspace", "identifier": "SpacesStore" },
                    "id": "44444444-4444-4444-4444-444444444444"
                  },
                  "nodeDbId": 4242,
                  "ancestorNodeRefs": [
                    { "storeRef": { "protocol": "workspace", "identifier": "SpacesStore" },
                      "id": "33333333-3333-3333-3333-333333333333" }
                  ],
                  "properties": { "cm:name": "piece.pdf" }
                }
                """;

        AuditEventMessage m = new ObjectMapper().readValue(json, AuditEventMessage.class);
        SolrInputDocument doc = mapper.toDocument(m, 0, 0L, 0L);

        assertEquals("workspace://SpacesStore/44444444-4444-4444-4444-444444444444",
                doc.getFieldValue("NODE_REF"));
        assertEquals("44444444-4444-4444-4444-444444444444", doc.getFieldValue("NODE_UUID"));
        assertEquals(4242L, doc.getFieldValue("NODE_DBID"));
        assertEquals(1, doc.getFieldValues("ANCESTOR").size());
        assertEquals("workspace://SpacesStore/33333333-3333-3333-3333-333333333333",
                doc.getFieldValues("ANCESTOR").iterator().next());
    }

    @Test
    public void toleratesAMessageWithNoProperties()
    {
        AuditEventMessage m = message();
        m.setProperties(null);

        SolrInputDocument doc = mapper.toDocument(m, 0, 0L, 0L);

        assertNull(doc.getFieldValue("NODE_NAME"));
        assertNull(doc.getFieldValue("PROPERTIES_JSON"));
    }
}
