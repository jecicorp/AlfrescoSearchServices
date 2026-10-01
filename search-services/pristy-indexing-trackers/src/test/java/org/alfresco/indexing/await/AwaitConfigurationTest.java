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

package org.alfresco.indexing.await;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.solr.client.SOLRAPIClient;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class AwaitConfigurationTest
{
    private final TrackerProperties properties = new TrackerProperties();

    @Test
    public void theAwaitedCoreIsTheOneTrackingTheWorkspaceStore()
    {
        properties.getSolr().setCollections(List.of("archive", "alfresco"));

        assertEquals("alfresco", AwaitConfiguration.workspaceCore(properties));
    }

    @Test
    public void aStoreOverrideMovesTheAwaitedCore()
    {
        properties.getSolr().setCollections(List.of("alfresco", "live"));
        TrackerProperties.CoreConfig archived = new TrackerProperties.CoreConfig();
        archived.setStore(TrackerProperties.ARCHIVE_STORE);
        properties.getCores().put("alfresco", archived);

        assertEquals("live", AwaitConfiguration.workspaceCore(properties));
    }

    @Test
    public void withoutAWorkspaceCoreThereIsNone()
    {
        properties.getSolr().setCollections(List.of("archive"));

        assertNull(AwaitConfiguration.workspaceCore(properties));
    }

    @Test
    public void theAwaitThreadsAreNamedDaemons()
    {
        Thread thread = AwaitConfiguration.daemon("index-await-stream").newThread(() -> { });

        assertEquals("index-await-stream", thread.getName());
        assertTrue(thread.isDaemon());
    }

    @Test
    public void disabledTheEndpointIsNotRegistered()
    {
        new ApplicationContextRunner()
                .withPropertyValues("alfresco.tracker.await.enabled=false")
                .withUserConfiguration(AwaitConfiguration.class, AwaitController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AwaitService.class).isEmpty());
                    assertTrue(context.getBeansOfType(AwaitController.class).isEmpty());
                });
    }

    @Test
    public void byDefaultTheEndpointIsRegistered()
    {
        new ApplicationContextRunner()
                .withBean(TrackerProperties.class, TrackerProperties::new)
                .withBean(TrackerBootstrap.class, () -> mock(TrackerBootstrap.class))
                .withBean(SolrClient.class, () -> mock(SolrClient.class))
                .withBean(SOLRAPIClient.class, () -> mock(SOLRAPIClient.class))
                .withUserConfiguration(AwaitConfiguration.class, AwaitController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(AwaitService.class).size());
                    assertEquals(1, context.getBeansOfType(AwaitController.class).size());
                });
    }
}
