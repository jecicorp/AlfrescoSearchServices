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

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.config.TrackerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the progress sampling and its endpoints.
 *
 * <p>The cadence is registered programmatically rather than through a {@code @Scheduled}
 * placeholder, so the interval keeps a single default, in
 * {@link TrackerProperties.ProgressConfig}.
 */
@Configuration
public class ProgressConfiguration implements SchedulingConfigurer, WebMvcConfigurer
{
    private final TrackerProperties properties;
    private final ObjectProvider<ProgressSampler> sampler;

    public ProgressConfiguration(TrackerProperties properties, ObjectProvider<ProgressSampler> sampler)
    {
        this.properties = properties;
        this.sampler = sampler;
    }

    @Bean
    ProgressSource progressSource(TrackerBootstrap bootstrap)
    {
        return new TrackerRegistryProgressSource(bootstrap);
    }

    @Bean
    ProgressService progressService(ProgressSource source)
    {
        return new ProgressService(source, Clock.systemUTC(),
                Duration.ofSeconds(properties.getProgress().getWindowSeconds()));
    }

    @Bean
    ProgressBroadcaster progressBroadcaster(ProgressService service)
    {
        return new ProgressBroadcaster(service);
    }

    @Bean
    ProgressSampler progressSampler(ProgressService service, ProgressBroadcaster broadcaster)
    {
        return new ProgressSampler(service, broadcaster);
    }

    @Bean
    ProgressController progressController(ProgressService service, ProgressBroadcaster broadcaster)
    {
        return new ProgressController(service, broadcaster,
                properties.getProgress().getStreamTimeoutMillis());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar)
    {
        Duration interval = Duration.ofMillis(properties.getProgress().getSampleIntervalMillis());
        registrar.addFixedDelayTask(() -> sampler.getObject().tick(), interval);
    }

    /**
     * Cross-origin access is off until an origin is named, so the endpoints keep the
     * same exposure as the rest of the admin surface unless an operator opts in.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry)
    {
        List<String> origins = properties.getProgress().getCorsAllowedOrigins();
        if (origins.isEmpty())
        {
            return;
        }
        registry.addMapping("/api/v1/progress/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET");
    }
}
