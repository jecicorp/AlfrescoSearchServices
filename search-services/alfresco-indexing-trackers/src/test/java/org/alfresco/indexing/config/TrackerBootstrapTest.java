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
package org.alfresco.indexing.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link TrackerBootstrap}.
 *
 * <p>These tests verify the property bridging logic (Spring Boot
 * {@link TrackerProperties} to legacy {@link Properties}) without
 * starting the full Spring context or creating real tracker instances.</p>
 */
public class TrackerBootstrapTest
{
    private TrackerProperties props;
    private Properties repoProperties;

    @Before
    public void setUp()
    {
        props = new TrackerProperties();
        props.getSolr().setUrl("http://solr:8983/solr");
        props.getSolr().setCollection("test-core");
        props.getRepository().setUrl("http://repo:8080/alfresco");
        props.getRepository().setSecureComms("secret");
        props.getRepository().setSharedSecret("s3cret");
        props.setBatchCount(1000);
        props.setCascadeTrackingEnabled(true);
        props.setSolrHome("/tmp/solr-home");
        props.getCron().setMetadata("0/5 * * * * ?");
        props.getCron().setAcl("0/15 * * * * ?");
        props.getCron().setContent("0/20 * * * * ?");
        props.getCron().setCommit("0/30 * * * * ?");
        props.getCron().setModel("0/60 * * * * ?");
        props.getCron().setCascade("0/10 * * * * ?");

        // Simulate the repositoryProperties bean
        repoProperties = new Properties();
        repoProperties.setProperty("alfresco.host", "repo");
        repoProperties.setProperty("alfresco.port", "8080");
        repoProperties.setProperty("alfresco.baseUrl", "/alfresco");
        repoProperties.setProperty("alfresco.secureComms", "secret");
        repoProperties.setProperty("alfresco.batch.count", "1000");
    }

    @Test
    public void buildTrackerProperties_bridgesCronSchedules()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties();

        assertEquals("0/5 * * * * ?", result.getProperty("alfresco.metadata.tracker.cron"));
        assertEquals("0/15 * * * * ?", result.getProperty("alfresco.acl.tracker.cron"));
        assertEquals("0/20 * * * * ?", result.getProperty("alfresco.content.tracker.cron"));
        assertEquals("0/30 * * * * ?", result.getProperty("alfresco.commit.tracker.cron"));
        assertEquals("0/60 * * * * ?", result.getProperty("alfresco.model.tracker.cron"));
        assertEquals("0/10 * * * * ?", result.getProperty("alfresco.cascade.tracker.cron"));
    }

    @Test
    public void buildTrackerProperties_includesRepositoryProperties()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties();

        assertEquals("repo", result.getProperty("alfresco.host"));
        assertEquals("8080", result.getProperty("alfresco.port"));
        assertEquals("/alfresco", result.getProperty("alfresco.baseUrl"));
        assertEquals("secret", result.getProperty("alfresco.secureComms"));
        assertEquals("1000", result.getProperty("alfresco.batch.count"));
    }

    @Test
    public void buildTrackerProperties_includesCascadeEnabledFlag()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties();

        assertEquals("true", result.getProperty("alfresco.cascade.tracker.enabled"));
    }

    @Test
    public void buildTrackerProperties_cascadeDisabled()
    {
        props.setCascadeTrackingEnabled(false);
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties();

        assertEquals("false", result.getProperty("alfresco.cascade.tracker.enabled"));
    }

    @Test
    public void shutdown_handlesNullSchedulerGracefully()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        // Should not throw — scheduler and trackers are empty
        bootstrap.shutdown();

        assertNotNull(bootstrap.getTrackers());
        assertTrue(bootstrap.getTrackers().isEmpty());
    }
}
