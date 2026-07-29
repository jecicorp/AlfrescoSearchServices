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

        Properties result = bootstrap.buildTrackerProperties("test-core");

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

        Properties result = bootstrap.buildTrackerProperties("test-core");

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

        Properties result = bootstrap.buildTrackerProperties("test-core");

        assertEquals("true", result.getProperty("alfresco.cascade.tracker.enabled"));
    }

    @Test
    public void buildTrackerProperties_bridgesDefaultContentLimits()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties("test-core");

        assertEquals("2000", result.getProperty("alfresco.contentUpdateBatchSize"));
        assertEquals("8", result.getProperty("alfresco.content.tracker.maxParallelism"));
        assertEquals("2000", result.getProperty("alfresco.content.tracker.maxDocumentsPerCycle"));
    }

    @Test
    public void buildTrackerProperties_cascadeDisabled()
    {
        props.setCascadeTrackingEnabled(false);
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        Properties result = bootstrap.buildTrackerProperties("test-core");

        assertEquals("false", result.getProperty("alfresco.cascade.tracker.enabled"));
    }

    @Test
    public void buildTrackerProperties_defaultsStoreFromCoreName()
    {
        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        // No explicit store override → convention: archive core tracks the archive store,
        // every other core tracks the workspace store.
        assertEquals("workspace://SpacesStore",
                bootstrap.buildTrackerProperties("alfresco").getProperty("alfresco.stores"));
        assertEquals("archive://SpacesStore",
                bootstrap.buildTrackerProperties("archive").getProperty("alfresco.stores"));
    }

    @Test
    public void buildTrackerProperties_perCoreOverridesLayerOverGlobal()
    {
        // Global tuning
        props.setBatchCount(5000);
        props.setTransformContent(true);
        props.setCommitInterval(2000);
        props.getContent().setBatchSize(100);
        props.getContent().setMaxParallelism(4);
        props.getContent().setMaxDocumentsPerCycle(500);
        props.getCron().setMetadata("0/10 * * * * ?");

        // The archive core is secondary: smaller batches, no content extraction,
        // slower commit and a sparse metadata schedule.
        TrackerProperties.CoreConfig archive = new TrackerProperties.CoreConfig();
        archive.setBatchCount(1000);
        archive.setTransformContent(false);
        archive.setCommitInterval(30000L);
        archive.getContent().setBatchSize(25);
        archive.getContent().setMaxParallelism(1);
        archive.getContent().setMaxDocumentsPerCycle(75);
        archive.getCron().setMetadata("0 0/5 * * * ?");
        props.getCores().put("archive", archive);

        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        // Primary core inherits all global values.
        Properties primary = bootstrap.buildTrackerProperties("alfresco");
        assertEquals("5000", primary.getProperty("alfresco.batch.count"));
        assertEquals("true", primary.getProperty("alfresco.index.transformContent"));
        assertEquals("2000", primary.getProperty("alfresco.commitInterval"));
        assertEquals("100", primary.getProperty("alfresco.contentUpdateBatchSize"));
        assertEquals("4", primary.getProperty("alfresco.content.tracker.maxParallelism"));
        assertEquals("500", primary.getProperty("alfresco.content.tracker.maxDocumentsPerCycle"));
        assertEquals("0/10 * * * * ?", primary.getProperty("alfresco.metadata.tracker.cron"));

        // Archive core applies its overrides but inherits the rest (e.g. acl cron).
        Properties archiveProps = bootstrap.buildTrackerProperties("archive");
        assertEquals("1000", archiveProps.getProperty("alfresco.batch.count"));
        assertEquals("false", archiveProps.getProperty("alfresco.index.transformContent"));
        assertEquals("30000", archiveProps.getProperty("alfresco.commitInterval"));
        assertEquals("25", archiveProps.getProperty("alfresco.contentUpdateBatchSize"));
        assertEquals("1", archiveProps.getProperty("alfresco.content.tracker.maxParallelism"));
        assertEquals("75", archiveProps.getProperty("alfresco.content.tracker.maxDocumentsPerCycle"));
        assertEquals("0 0/5 * * * ?", archiveProps.getProperty("alfresco.metadata.tracker.cron"));
        assertEquals("0/15 * * * * ?", archiveProps.getProperty("alfresco.acl.tracker.cron"));
    }

    @Test
    public void buildTrackerProperties_explicitStoreOverridesConvention()
    {
        TrackerProperties.CoreConfig custom = new TrackerProperties.CoreConfig();
        custom.setStore("workspace://SpacesStore");
        props.getCores().put("archive", custom);

        TrackerBootstrap bootstrap = new TrackerBootstrap(null, props, null, repoProperties, null, null);

        assertEquals("workspace://SpacesStore",
                bootstrap.buildTrackerProperties("archive").getProperty("alfresco.stores"));
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
