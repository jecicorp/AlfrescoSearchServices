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

package org.alfresco.indexing.diagnostic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DiagnosticSnapshotContractTest
{
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    public void aRunningJobCarriesTheAgreedFields()
    {
        DiagnosticSnapshot snapshot = DiagnosticSnapshot
                .running("2026-09-25T15:02:11Z", "admin", List.of("alfresco", "archive"), null)
                .withCore("alfresco", new DiagnosticSnapshot.CoreProgress("acl.db", 41200L, 98000L), 3);

        JsonNode json = serialise(snapshot);

        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("state", "startedAt", "startedBy", "finishedAt", "step", "steps", "cores", "result", "error"),
                new HashSet<>(names));
        assertEquals("running", json.get("state").asText());
        assertEquals("2026-09-25T15:02:11Z", json.get("startedAt").asText());
        assertEquals("admin", json.get("startedBy").asText());
        assertTrue(json.get("finishedAt").isNull());
        assertEquals(3, json.get("step").asInt());
        assertEquals(9, json.get("steps").asInt());
        assertEquals("acl.db", json.at("/cores/alfresco/phase").asText());
        assertEquals(41200L, json.at("/cores/alfresco/current").asLong());
        assertEquals(98000L, json.at("/cores/alfresco/target").asLong());
        assertEquals("pending", json.at("/cores/archive/phase").asText());
        assertTrue(json.at("/cores/archive/current").isNull());
        assertTrue(json.at("/cores/archive/target").isNull());
        assertTrue(json.get("result").isNull());
        assertTrue(json.get("error").isNull());
    }

    @Test
    public void anIdleServiceWritesItsNullsExplicitly()
    {
        JsonNode json = serialise(DiagnosticSnapshot.idle());

        assertEquals("idle", json.get("state").asText());
        assertTrue(json.get("startedAt").isNull());
        assertTrue(json.get("step").isNull());
        assertEquals(0, json.get("cores").size());
    }

    @Test
    public void aFinishedJobMarksEveryCoreDoneAtTheLastStep()
    {
        DiagnosticSnapshot done = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin", "2026-09-25T15:04:40Z",
                List.of("alfresco", "archive"), Map.of("partialFailures", List.of()));

        JsonNode json = serialise(done);

        assertEquals("done", json.get("state").asText());
        assertEquals(9, json.get("step").asInt());
        assertEquals(9, json.get("steps").asInt());
        assertEquals("done", json.at("/cores/alfresco/phase").asText());
        assertEquals("done", json.at("/cores/archive/phase").asText());
        assertEquals("2026-09-25T15:04:40Z", json.get("finishedAt").asText());
    }

    private JsonNode serialise(DiagnosticSnapshot snapshot)
    {
        List<JsonNode> json = new ArrayList<>();
        context.run(ctx -> {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            json.add(mapper.readTree(mapper.writeValueAsString(snapshot)));
        });
        return json.get(0);
    }
}
