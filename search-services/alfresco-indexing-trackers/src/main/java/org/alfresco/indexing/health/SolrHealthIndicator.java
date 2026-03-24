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
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrPing;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class SolrHealthIndicator implements HealthIndicator
{
    private final SolrClient solrClient;
    private final String collection;

    public SolrHealthIndicator(SolrClient solrClient, TrackerProperties props)
    {
        this.solrClient = solrClient;
        this.collection = props.getSolr().getCollection();
    }

    @Override
    public Health health()
    {
        try
        {
            SolrPing ping = new SolrPing();
            ping.process(solrClient, collection);
            return Health.up().withDetail("collection", collection).build();
        }
        catch (Exception e)
        {
            return Health.down(e).withDetail("collection", collection).build();
        }
    }
}
