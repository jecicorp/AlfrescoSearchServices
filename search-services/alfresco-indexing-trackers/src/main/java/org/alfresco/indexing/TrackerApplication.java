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
package org.alfresco.indexing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.alfresco.indexing.config.TrackerProperties;

/**
 * Spring Boot entry point for the Alfresco Indexing Trackers standalone service.
 */
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration.class
})
@EnableConfigurationProperties(TrackerProperties.class)
public class TrackerApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(TrackerApplication.class, args);
    }
}
