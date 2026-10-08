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
package org.alfresco.indexing.audit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

public class AuditPurgeSchedulerTest
{
    private static SolrClient clientMatching(long numFound) throws Exception
    {
        SolrClient solrClient = Mockito.mock(SolrClient.class);
        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(numFound);
        QueryResponse response = Mockito.mock(QueryResponse.class);
        Mockito.when(response.getResults()).thenReturn(results);
        Mockito.when(solrClient.query(Mockito.eq("audit"), Mockito.any(SolrQuery.class))).thenReturn(response);
        return solrClient;
    }

    @Test
    public void deletesEverythingOlderThanTheRetention() throws Exception
    {
        SolrClient solrClient = clientMatching(7);

        new AuditPurgeScheduler(solrClient, "audit", 1825).purge();

        Mockito.verify(solrClient).deleteByQuery("audit", "EVENT_TIME:[* TO NOW-1825DAYS]");
        Mockito.verify(solrClient).commit("audit");
    }

    @Test
    public void deletesNothingWhenNoDocumentIsOldEnough() throws Exception
    {
        SolrClient solrClient = clientMatching(0);

        new AuditPurgeScheduler(solrClient, "audit", 1825).purge();

        Mockito.verify(solrClient, Mockito.never()).deleteByQuery(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(solrClient, Mockito.never()).commit(Mockito.anyString());
    }

    @Test
    public void doesNothingWhenRetentionIsNotPositive() throws Exception
    {
        SolrClient solrClient = Mockito.mock(SolrClient.class);

        new AuditPurgeScheduler(solrClient, "audit", 0).purge();

        Mockito.verifyNoInteractions(solrClient);
    }

    @Test
    public void reportsTheRetentionItWouldApply()
    {
        AuditPurgeScheduler scheduler =
                new AuditPurgeScheduler(Mockito.mock(SolrClient.class), "audit", 90);

        assertEquals("EVENT_TIME:[* TO NOW-90DAYS]", scheduler.purgeQuery());
    }
}
