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

package org.alfresco.indexing.progress;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the wire contract that pristy-portail reads.
 *
 * <p>These names and this instant format are consumed outside this repository, so
 * renaming a record component is a breaking change rather than a refactoring.
 */
public class ProgressPayloadContractTest
{
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    private String serialise(ProgressSnapshot snapshot)
    {
        StringBuilder json = new StringBuilder();
        context.run(ctx -> json.append(ctx.getBean(ObjectMapper.class).writeValueAsString(snapshot)));
        return json.toString();
    }

    @Test
    public void serialisesTheAgreedFieldNamesAndAnIsoInstant()
    {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                Instant.parse("2026-09-22T10:00:00Z"),
                List.of(new CoreProgress("alfresco", List.of(new TrackerProgress(
                        "metadata", true, 4_000L, 250L, 375L, 1_000L, 42.5d, 6L,
                        ProgressRate.Trend.FALLING,
                        Instant.parse("2026-09-22T09:59:58Z"))))));

        String json = serialise(snapshot);

        assertTrue(json, json.contains("\"generatedAt\":\"2026-09-22T10:00:00Z\""));
        assertTrue(json, json.contains("\"cores\""));
        assertTrue(json, json.contains("\"core\":\"alfresco\""));
        assertTrue(json, json.contains("\"tracker\":\"metadata\""));
        assertTrue(json, json.contains("\"active\":true"));
        assertTrue(json, json.contains("\"done\":4000"));
        assertTrue(json, json.contains("\"remaining\":250"));
        assertTrue(json, json.contains("\"remainingNodes\":375"));
        assertTrue(json, json.contains("\"peakRemaining\":1000"));
        assertTrue(json, json.contains("\"ratePerSec\":42.5"));
        assertTrue(json, json.contains("\"etaSeconds\":6"));
        assertTrue(json, json.contains("\"trend\":\"FALLING\""));
        assertTrue(json, json.contains("\"lastSampleAt\":\"2026-09-22T09:59:58Z\""));
    }

    @Test
    public void keepsAnUnmeasuredFigureExplicitlyNull()
    {
        ProgressSnapshot snapshot = new ProgressSnapshot(Instant.parse("2026-09-22T10:00:00Z"),
                List.of(new CoreProgress("alfresco", List.of(new TrackerProgress(
                        "cascade", true, null, null, null, null, null, null, null, null)))));

        String json = serialise(snapshot);

        assertTrue("an omitted field would read as zero on the client side",
                json.contains("\"etaSeconds\":null"));
        assertTrue(json, json.contains("\"remaining\":null"));
    }

    @Test
    public void shipsTheStandalonePageInsideTheJar()
    {
        assertNotNull("the demo page must travel with the service, not with a web server",
                getClass().getClassLoader().getResource("static/indexing.html"));
    }
}
