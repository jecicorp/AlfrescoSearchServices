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
package org.alfresco.indexing.progress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.alfresco.indexing.config.TrackerProperties;
import org.junit.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * Checks the cross-origin rule of the unauthenticated progress endpoints.
 */
public class ProgressConfigurationCorsTest
{
    private static final class ExposedRegistry extends CorsRegistry
    {
        Map<String, CorsConfiguration> mappings()
        {
            return getCorsConfigurations();
        }
    }

    private Map<String, CorsConfiguration> mappingsFor(List<String> origins)
    {
        TrackerProperties properties = new TrackerProperties();
        properties.getProgress().setCorsAllowedOrigins(origins);
        ExposedRegistry registry = new ExposedRegistry();
        new ProgressConfiguration(properties, null).addCorsMappings(registry);
        return registry.mappings();
    }

    @Test
    public void crossOriginAccessIsOffByDefault()
    {
        assertTrue(mappingsFor(new TrackerProperties().getProgress().getCorsAllowedOrigins()).isEmpty());
    }

    @Test
    public void crossOriginAccessIsOffWhenNoOriginIsNamed()
    {
        assertTrue(mappingsFor(List.of()).isEmpty());
    }

    @Test
    public void aNamedOriginOnlyOpensTheProgressApiToGet()
    {
        Map<String, CorsConfiguration> mappings = mappingsFor(List.of("https://portal.example"));

        assertEquals(List.of("/api/v1/progress/**"), List.copyOf(mappings.keySet()));
        CorsConfiguration progress = mappings.get("/api/v1/progress/**");
        assertEquals(List.of("https://portal.example"), progress.getAllowedOrigins());
        assertEquals(List.of("GET"), progress.getAllowedMethods());
    }
}
