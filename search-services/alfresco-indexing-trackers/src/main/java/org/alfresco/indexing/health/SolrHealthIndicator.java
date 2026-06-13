/*
 * Copyright 2026 - Jeci SARL - https://jeci.fr
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * http://www.gnu.org/licenses/.
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
