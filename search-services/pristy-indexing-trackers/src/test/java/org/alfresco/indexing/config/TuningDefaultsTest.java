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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Map;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the single source of tuning defaults.
 *
 * <p>The packaged {@code application.yml} ships inside the jar and therefore wins over the
 * field initialisers of {@link TrackerProperties.TuningConfig}. Declaring a global default
 * in both places is how the cron defaults came to disagree — {@code TrackerProperties.Cron}
 * says 0/10 for metadata while the yml says 0/5, and only the yml runs. The tuning knobs
 * keep their defaults in Java alone, and the yml carries per-core overrides only.
 */
public class TuningDefaultsTest
{
    @Test
    @SuppressWarnings("unchecked")
    public void thePackagedYmlDeclaresNoGlobalTuningDefault() throws Exception
    {
        Map<String, Object> tracker = trackerSection();

        assertFalse("application.yml must not repeat the TuningConfig defaults: "
                        + "two layers of defaults are free to drift apart, and the yml wins",
                tracker.containsKey("tuning"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void thePackagedYmlKeepsItsPerCoreOverrides() throws Exception
    {
        Map<String, Object> cores = (Map<String, Object>) trackerSection().get("cores");
        Map<String, Object> archive = (Map<String, Object>) cores.get("archive");
        Map<String, Object> tuning = (Map<String, Object>) archive.get("tuning");

        assertNotNull("the archive core must keep tuning it down", tuning);
        assertEquals(4, tuning.get("metadata-parallelism"));
    }

    @Test
    public void theMetadataPoolDefaultsToWhatTheBenchmarkMeasured()
    {
        TrackerProperties.TuningConfig tuning = new TrackerProperties.TuningConfig();

        assertEquals("docs/bench-large-folder.md: eight threads index as fast as thirty-two",
                8, tuning.getMetadataParallelism());
        assertTrue("a per-core override must still be able to cut it further",
                tuning.getMetadataParallelism() > 4);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trackerSection() throws Exception
    {
        try (InputStream yml = TuningDefaultsTest.class.getResourceAsStream("/application.yml"))
        {
            assertNotNull("application.yml must be on the classpath", yml);
            Map<String, Object> root = new Yaml().load(yml);
            Map<String, Object> alfresco = (Map<String, Object>) root.get("alfresco");
            return (Map<String, Object>) alfresco.get("tracker");
        }
    }
}
