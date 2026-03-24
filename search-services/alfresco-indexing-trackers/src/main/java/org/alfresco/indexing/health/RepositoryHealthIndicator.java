/*
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.health;

import org.alfresco.indexing.config.TrackerProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;

@Component
public class RepositoryHealthIndicator implements HealthIndicator
{
    private final String repositoryUrl;

    public RepositoryHealthIndicator(TrackerProperties props)
    {
        this.repositoryUrl = props.getRepository().getUrl();
    }

    @Override
    public Health health()
    {
        try
        {
            URL url = new URL(repositoryUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int statusCode = connection.getResponseCode();
            connection.disconnect();

            if (statusCode < 500)
            {
                return Health.up()
                        .withDetail("url", repositoryUrl)
                        .withDetail("statusCode", statusCode)
                        .build();
            }
            else
            {
                return Health.down()
                        .withDetail("url", repositoryUrl)
                        .withDetail("statusCode", statusCode)
                        .build();
            }
        }
        catch (Exception e)
        {
            return Health.down(e).withDetail("url", repositoryUrl).build();
        }
    }
}
