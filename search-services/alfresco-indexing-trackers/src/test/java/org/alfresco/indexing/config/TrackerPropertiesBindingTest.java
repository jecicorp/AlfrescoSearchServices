/*-
 * #%L
 * Alfresco Indexing Trackers
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
package org.alfresco.indexing.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

public class TrackerPropertiesBindingTest
{
    @Test
    public void defaultsPreserveExistingContentValues()
    {
        TrackerProperties properties = new TrackerProperties();

        assertEquals(2000, properties.getContent().getBatchSize());
        assertEquals(8, properties.getContent().getMaxParallelism());
        assertEquals(2000, properties.getContent().getMaxDocumentsPerCycle());
    }

    @Test
    public void globalContentPropertiesAreBound()
    {
        TrackerProperties properties = bind(new MapPropertySource("test", Map.of(
                "alfresco.tracker.content.batch-size", 50,
                "alfresco.tracker.content.max-parallelism", 2,
                "alfresco.tracker.content.max-documents-per-cycle", 250)));

        assertEquals(50, properties.getContent().getBatchSize());
        assertEquals(2, properties.getContent().getMaxParallelism());
        assertEquals(250, properties.getContent().getMaxDocumentsPerCycle());
    }

    @Test
    public void environmentVariableNamesUseSpringRelaxedBinding()
    {
        Map<String, Object> environment = new HashMap<>();
        environment.put("ALFRESCO_TRACKER_CONTENT_BATCH_SIZE", "40");
        environment.put("ALFRESCO_TRACKER_CONTENT_MAX_PARALLELISM", "1");
        environment.put("ALFRESCO_TRACKER_CONTENT_MAX_DOCUMENTS_PER_CYCLE", "120");
        environment.put("ALFRESCO_TRACKER_CORES_ARCHIVE_CONTENT_BATCH_SIZE", "10");
        environment.put("ALFRESCO_TRACKER_CORES_ARCHIVE_CONTENT_MAX_PARALLELISM", "2");
        environment.put("ALFRESCO_TRACKER_CORES_ARCHIVE_CONTENT_MAX_DOCUMENTS_PER_CYCLE", "30");
        environment.put("ALFRESCO_TRACKER_CORES_ALFRESCO_CONTENT_BATCH_SIZE", "12");
        environment.put("ALFRESCO_TRACKER_CORES_ALFRESCO_CONTENT_MAX_PARALLELISM", "3");
        environment.put("ALFRESCO_TRACKER_CORES_ALFRESCO_CONTENT_MAX_DOCUMENTS_PER_CYCLE", "36");

        TrackerProperties properties = bind(new SystemEnvironmentPropertySource("test-env", environment));

        assertEquals(40, properties.getContent().getBatchSize());
        assertEquals(1, properties.getContent().getMaxParallelism());
        assertEquals(120, properties.getContent().getMaxDocumentsPerCycle());
        assertEquals(12, properties.resolvedCore("alfresco").getContentBatchSize());
        assertEquals(3, properties.resolvedCore("alfresco").getContentMaxParallelism());
        assertEquals(36, properties.resolvedCore("alfresco").getContentMaxDocumentsPerCycle());
        assertEquals(10, properties.resolvedCore("archive").getContentBatchSize());
        assertEquals(2, properties.resolvedCore("archive").getContentMaxParallelism());
        assertEquals(30, properties.resolvedCore("archive").getContentMaxDocumentsPerCycle());
    }

    @Test
    public void perCoreContentValuesOverrideGlobalValuesIndependently()
    {
        TrackerProperties properties = bind(new MapPropertySource("test", Map.of(
                "alfresco.tracker.content.batch-size", 100,
                "alfresco.tracker.content.max-parallelism", 4,
                "alfresco.tracker.content.max-documents-per-cycle", 500,
                "alfresco.tracker.cores.archive.content.batch-size", 25,
                "alfresco.tracker.cores.archive.content.max-parallelism", 1,
                "alfresco.tracker.cores.archive.content.max-documents-per-cycle", 75)));

        TrackerProperties.ResolvedCoreConfig alfresco = properties.resolvedCore("alfresco");
        TrackerProperties.ResolvedCoreConfig archive = properties.resolvedCore("archive");

        assertEquals(100, alfresco.getContentBatchSize());
        assertEquals(4, alfresco.getContentMaxParallelism());
        assertEquals(500, alfresco.getContentMaxDocumentsPerCycle());
        assertEquals(25, archive.getContentBatchSize());
        assertEquals(1, archive.getContentMaxParallelism());
        assertEquals(75, archive.getContentMaxDocumentsPerCycle());
    }

    @Test
    public void invalidContentValuesFailBindingWithUsefulMessage()
    {
        assertInvalid("alfresco.tracker.content.batch-size", 0,
                "alfresco.tracker.content.batch-size must be greater than zero");
        assertInvalid("alfresco.tracker.content.max-parallelism", -1,
                "alfresco.tracker.content.max-parallelism must be greater than zero");
        assertInvalid("alfresco.tracker.content.max-documents-per-cycle", 0,
                "alfresco.tracker.content.max-documents-per-cycle must be greater than zero");
        assertInvalid("alfresco.tracker.cores.archive.content.max-documents-per-cycle", -10,
                "alfresco.tracker.cores.<core>.content.max-documents-per-cycle must be greater than zero");
    }

    private TrackerProperties bind(org.springframework.core.env.PropertySource<?> propertySource)
    {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(propertySource);
        return Binder.get(environment)
                .bind("alfresco.tracker", Bindable.of(TrackerProperties.class))
                .orElseGet(TrackerProperties::new);
    }

    private String causeMessages(Throwable throwable)
    {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null)
        {
            if (current.getMessage() != null)
            {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private void assertInvalid(String propertyName, int value, String expectedMessage)
    {
        try
        {
            bind(new MapPropertySource("test", Map.of(propertyName, value)));
            fail("Expected invalid content configuration to fail binding: " + propertyName);
        }
        catch (RuntimeException exception)
        {
            assertTrue(causeMessages(exception).contains(expectedMessage));
        }
    }
}
