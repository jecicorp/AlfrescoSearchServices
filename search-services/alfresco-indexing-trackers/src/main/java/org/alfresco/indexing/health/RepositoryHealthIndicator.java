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
    private final int connectTimeout;
    private final int readTimeout;

    public RepositoryHealthIndicator(TrackerProperties props)
    {
        this.repositoryUrl = props.getRepository().getUrl();
        this.connectTimeout = props.getHealth().getConnectTimeout();
        this.readTimeout = props.getHealth().getReadTimeout();
    }

    @Override
    public Health health()
    {
        try
        {
            URL url = new URL(repositoryUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
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
