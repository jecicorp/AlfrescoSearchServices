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
import org.mockito.Mockito;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditKafkaConfigTest
{
    @SuppressWarnings("unchecked")
    private ConcurrentKafkaListenerContainerFactory<String, AuditEventMessage> factory()
    {
        ConsumerFactory<String, AuditEventMessage> consumers = Mockito.mock(ConsumerFactory.class);
        return new AuditKafkaConfig().auditListenerContainerFactory(consumers);
    }

    @Test
    public void theFactoryRetriesWithoutEverGivingUp()
    {
        Object handler = ReflectionTestUtils.getField(factory(), "commonErrorHandler");

        assertTrue(handler instanceof DefaultErrorHandler);
        Object tracker = ReflectionTestUtils.invokeMethod(handler, "getFailureTracker");
        FixedBackOff backOff = (FixedBackOff) ReflectionTestUtils.getField(tracker, "backOff");
        assertEquals(FixedBackOff.UNLIMITED_ATTEMPTS, backOff.getMaxAttempts());
        assertEquals(AuditKafkaConfig.RETRY_INTERVAL_MILLIS, backOff.getInterval());
    }

    @Test
    public void theFactoryCommitsRecordByRecord()
    {
        assertEquals(ContainerProperties.AckMode.RECORD, factory().getContainerProperties().getAckMode());
    }

    @Test
    public void aDeserializationFailureIsNotRetried()
    {
        DefaultErrorHandler handler = AuditKafkaConfig.auditErrorHandler();
        BinaryExceptionClassifier classifier = ReflectionTestUtils.invokeMethod(handler, "getClassifier");

        assertFalse(classifier.classify(new DeserializationException("bad", new byte[0], false, new RuntimeException())));
    }
}
