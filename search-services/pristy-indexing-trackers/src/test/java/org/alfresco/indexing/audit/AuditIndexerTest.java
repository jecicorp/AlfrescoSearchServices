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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class AuditIndexerTest
{
    private SolrClient solrClient;
    private AuditIndexer indexer;

    @Before
    public void setUp()
    {
        this.solrClient = Mockito.mock(SolrClient.class);
        this.indexer = new AuditIndexer(solrClient, new AuditDocumentMapper(new ObjectMapper()), "audit");
    }

    private ConsumerRecord<String, AuditEventMessage> record(String id, int partition, long offset)
    {
        AuditEventMessage m = new AuditEventMessage();
        m.setId(id);
        m.setTimestamp(1767225600000L);
        m.setEventType("create-node");
        m.setAuthenticatedUser("abeecher");
        AuditEventMessage.StoreRefDto store = new AuditEventMessage.StoreRefDto();
        store.setProtocol("workspace");
        store.setIdentifier("SpacesStore");
        AuditEventMessage.NodeRefDto ref = new AuditEventMessage.NodeRefDto();
        ref.setStoreRef(store);
        ref.setId("44444444-4444-4444-4444-444444444444");
        m.setNodeRef(ref);
        m.setNodeDbId(4242L);
        m.setProperties(Map.of("cm:name", "piece.pdf"));
        return new ConsumerRecord<>("audit-pristy", partition, offset, id, m);
    }

    @Test
    public void indexesOneDocumentPerRecord() throws Exception
    {
        indexer.consume(record("event-1", 2, 77L));

        ArgumentCaptor<SolrInputDocument> captor = ArgumentCaptor.forClass(SolrInputDocument.class);
        Mockito.verify(solrClient).add(Mockito.eq("audit"), captor.capture());

        assertEquals("event-1", captor.getValue().getFieldValue("id"));
        assertEquals(2L, captor.getValue().getFieldValue("KAFKA_PARTITION"));
        assertEquals(77L, captor.getValue().getFieldValue("KAFKA_OFFSET"));
    }

    @Test
    public void forwardsTheRecordTimestampToTheMapper()
    {
        AuditEventMessage message = record("event-4", 2, 77L).value();
        ConsumerRecord<String, AuditEventMessage> stamped = new ConsumerRecord<>("audit-pristy", 2, 77L, 555L,
                TimestampType.CREATE_TIME, 0, 0, "event-4", message, new RecordHeaders(), Optional.empty());
        AuditDocumentMapper spy = Mockito.mock(AuditDocumentMapper.class);
        Mockito.when(spy.toDocument(message, 2, 77L, 555L)).thenReturn(new SolrInputDocument());

        new AuditIndexer(solrClient, spy, "audit").consume(stamped);

        Mockito.verify(spy).toDocument(message, 2, 77L, 555L);
    }

    @Test(expected = AuditException.class)
    public void propagatesASolrFailureSoTheOffsetIsNotCommitted() throws Exception
    {
        Mockito.when(solrClient.add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class)))
                .thenThrow(new SolrServerException("solr is down"));

        indexer.consume(record("event-2", 0, 1L));
    }

    @Test
    public void skipsARecordWithNoPayload() throws IOException, SolrServerException
    {
        indexer.consume(new ConsumerRecord<>("audit-pristy", 0, 5L, "k", null));

        Mockito.verifyNoInteractions(solrClient);
    }

    @Test
    public void skipsARecordWithNoIdWithoutTouchingSolr()
    {
        indexer.consume(record(null, 0, 6L));

        Mockito.verifyNoInteractions(solrClient);
    }

    @Test
    public void aMappingFailureDoesNotEscapeNorReachSolr()
    {
        AuditDocumentMapper failing = Mockito.mock(AuditDocumentMapper.class);
        Mockito.when(failing.toDocument(Mockito.any(), Mockito.anyInt(), Mockito.anyLong(), Mockito.anyLong()))
                .thenThrow(new IllegalStateException("unreadable"));
        AuditIndexer strict = new AuditIndexer(solrClient, failing, "audit");

        strict.consume(record("event-3", 1, 9L));

        Mockito.verifyNoInteractions(solrClient);
    }

    @Test
    public void aSolrRejectionIsSkippedSoThePartitionIsNotStalled() throws Exception
    {
        Mockito.when(solrClient.add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class)))
                .thenThrow(new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown field"));

        indexer.consume(record("event-4", 0, 11L));

        Mockito.verify(solrClient).add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class));
    }

    @Test(expected = AuditException.class)
    public void aServerErrorIsRetried() throws Exception
    {
        Mockito.when(solrClient.add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class)))
                .thenThrow(new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "overloaded"));

        indexer.consume(record("event-5", 0, 12L));
    }

    @Test(expected = AuditException.class)
    public void aThrottledWriteIsRetried() throws Exception
    {
        Mockito.when(solrClient.add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class)))
                .thenThrow(new SolrException(SolrException.ErrorCode.getErrorCode(429), "slow down"));

        indexer.consume(record("event-6", 0, 13L));
    }

    @Test(expected = AuditException.class)
    public void aConnectionFailureIsRetried() throws Exception
    {
        Mockito.when(solrClient.add(Mockito.eq("audit"), Mockito.any(SolrInputDocument.class)))
                .thenThrow(new IOException("connection refused"));

        indexer.consume(record("event-7", 0, 14L));
    }
}
