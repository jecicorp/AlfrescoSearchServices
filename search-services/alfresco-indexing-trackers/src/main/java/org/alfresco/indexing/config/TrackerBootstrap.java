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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.alfresco.indexing.server.solrj.LocalDictionaryService;
import org.alfresco.indexing.server.solrj.SolrJInformationServer;
import org.alfresco.indexing.server.solrj.SolrJModelService;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.dictionary.M2Namespace;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.CascadeTracker;
import org.alfresco.indexing.tracker.CommitTracker;
import org.alfresco.indexing.tracker.ContentTracker;
import org.alfresco.indexing.tracker.DataModelCallback;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.ModelTracker;
import org.alfresco.indexing.tracker.Tracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.indexing.tracker.TrackerScheduler;
import org.alfresco.indexing.tracker.repair.RepairTracker;
import org.alfresco.indexing.tracker.repair.RepairStrategy;
import org.alfresco.indexing.tracker.repair.UnresolvedModelStrategy;
import org.alfresco.indexing.tracker.repair.EmptyNodeStrategy;
import org.alfresco.repo.dictionary.NamespaceDAO;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AlfrescoModel;
import org.alfresco.solr.client.SOLRAPIClient;
import org.apache.solr.client.solrj.SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Spring Boot {@link ApplicationRunner} that initialises the tracking subsystem.
 *
 * <p>This is the Spring Boot equivalent of
 * {@code SolrCoreLoadListener.createAndScheduleCoreTrackers()} plus the
 * model-tracker creation logic from {@code SolrCoreLoadListener.createModelTracker()}.
 * It wires together:</p>
 * <ul>
 *   <li>{@link SolrJInformationServer} as the bridge to Solr (via SolrJ)</li>
 *   <li>{@link SOLRAPIClient} as the bridge to the Alfresco Repository</li>
 *   <li>All tracker instances (Model, ACL, Content, Metadata, Cascade, Commit)</li>
 *   <li>{@link TrackerScheduler} for Quartz-based scheduling</li>
 * </ul>
 */
@Component
public class TrackerBootstrap implements ApplicationRunner
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackerBootstrap.class);

    private final SolrClient solrClient;
    private final TrackerProperties props;
    private final SOLRAPIClient repoClient;
    private final Properties repositoryProperties;
    private final NamespaceDAO localNamespaceDAO;
    private final LocalDictionaryService localDictionaryService;

    private TrackerScheduler scheduler;
    private TrackerRegistry registry;
    private final List<Tracker> trackers = new ArrayList<>();

    public TrackerBootstrap(SolrClient solrClient,
                            TrackerProperties props,
                            SOLRAPIClient repoClient,
                            @Qualifier("repositoryProperties") Properties repositoryProperties,
                            NamespaceDAO localNamespaceDAO,
                            LocalDictionaryService localDictionaryService)
    {
        this.localNamespaceDAO = localNamespaceDAO;
        this.localDictionaryService = localDictionaryService;
        this.solrClient = solrClient;
        this.props = props;
        this.repoClient = repoClient;
        this.repositoryProperties = repositoryProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception
    {
        String coreName = props.getSolr().getCollection();

        // Build the legacy Properties that trackers read from.
        // Start from repositoryProperties (host, port, baseUrl, secureComms, etc.)
        // and add cron + tracker-specific properties.
        Properties trackerProps = buildTrackerProperties();

        LOGGER.info("Initialising tracking subsystem for collection '{}'", coreName);

        // 1. Create DataModelCallback (remote/SolrJ mode):
        //    afterInitModels sends an explicit request to the Solr handler.
        //    removeModel calls the Solr handler to remove the model from the dictionary.
        SolrJModelService modelService = new SolrJModelService(solrClient, coreName);
        DataModelCallback dataModelCallback = new DataModelCallback()
        {
            @Override
            public void afterInitModels()
            {
                modelService.afterInitModels();
            }

            @Override
            public void removeModel(QName modelName)
            {
                modelService.removeModel(modelName);
            }
        };

        // 2. Create SolrJInformationServer
        SolrJInformationServer infoSrv = new SolrJInformationServer(
                solrClient, coreName, trackerProps, dataModelCallback, repoClient, localDictionaryService);

        // 3. Set the local NamespaceDAO for QName resolution
        infoSrv.setNamespaceDAO(localNamespaceDAO);

        // 4. Create TrackerRegistry and attach it to the information server
        registry = new TrackerRegistry();
        infoSrv.setTrackerRegistry(registry);

        // 4. Create and start the Quartz scheduler
        scheduler = new TrackerScheduler(coreName);

        // 5. Create and schedule ModelTracker
        String solrHome = props.getSolrHome();
        ModelTracker modelTracker = new ModelTracker(
                solrHome, trackerProps, repoClient, coreName, infoSrv, dataModelCallback);
        registry.setModelTracker(modelTracker);

        LOGGER.info("ModelTracker: ensuring first model sync.");
        modelTracker.ensureFirstModelSync();

        // The ModelTracker only syncs diffs — models already in Solr are never
        // pushed via putModel(), so the local dictionary misses them.
        // Load all models from Solr into the local dictionary, respecting
        // dependency order (same approach as ModelTracker.loadPersistedModels).
        loadSolrModelsIntoLocalDictionary(infoSrv);

        scheduler.schedule(modelTracker, coreName, trackerProps);
        LOGGER.info("ModelTracker has been initialised, registered and scheduled.");

        // 6. Create and schedule core trackers (ACL, Content, Metadata, Cascade)
        AclTracker aclTracker = new AclTracker(trackerProps, repoClient, coreName, infoSrv);
        registry.register(coreName, aclTracker);
        scheduler.schedule(aclTracker, coreName, trackerProps);
        LOGGER.info("AclTracker registered and scheduled.");

        ContentTracker contentTracker = new ContentTracker(trackerProps, repoClient, coreName, infoSrv);
        registry.register(coreName, contentTracker);
        scheduler.schedule(contentTracker, coreName, trackerProps);
        LOGGER.info("ContentTracker registered and scheduled.");

        MetadataTracker metadataTracker = new MetadataTracker(trackerProps, repoClient, coreName, infoSrv, true);
        registry.register(coreName, metadataTracker);
        scheduler.schedule(metadataTracker, coreName, trackerProps);
        LOGGER.info("MetadataTracker registered and scheduled.");

        // Order for CommitTracker lock acquisition: content first, then metadata, then acl
        List<Tracker> coreTrackers = new ArrayList<>(Arrays.asList(contentTracker, metadataTracker, aclTracker));

        if (props.isCascadeTrackingEnabled())
        {
            CascadeTracker cascadeTracker = new CascadeTracker(trackerProps, repoClient, coreName, infoSrv);
            registry.register(coreName, cascadeTracker);
            scheduler.schedule(cascadeTracker, coreName, trackerProps);
            coreTrackers.add(0, cascadeTracker); // Add before content in the list
            LOGGER.info("CascadeTracker registered and scheduled.");
        }
        else
        {
            LOGGER.info("CascadeTracker is disabled.");
        }

        // 7. Create and schedule CommitTracker (must reference all other trackers)
        CommitTracker commitTracker = new CommitTracker(trackerProps, repoClient, coreName, infoSrv, coreTrackers);
        registry.register(coreName, commitTracker);
        scheduler.schedule(commitTracker, coreName, trackerProps);
        LOGGER.info("CommitTracker registered and scheduled.");

        // 8. Create and schedule RepairTracker
        List<RepairStrategy> repairStrategies = List.of(
                new UnresolvedModelStrategy(repoClient, infoSrv, localDictionaryService),
                new EmptyNodeStrategy(repoClient, infoSrv)
        );
        RepairTracker repairTracker = new RepairTracker(
                trackerProps, repoClient, coreName, infoSrv,
                repairStrategies, registry, props.getRepairMaxRetries());
        registry.register(coreName, repairTracker);
        scheduler.schedule(repairTracker, coreName, trackerProps);
        LOGGER.info("RepairTracker registered and scheduled with {} strategies.", repairStrategies.size());

        // Keep references for shutdown
        trackers.addAll(coreTrackers);
        trackers.add(commitTracker);
        trackers.add(repairTracker);

        LOGGER.info("Tracking subsystem fully initialised for collection '{}': {} trackers active.",
                coreName, trackers.size() + 1 /* +1 for ModelTracker */);
    }

    /**
     * Builds the flat {@link Properties} expected by tracker constructors and
     * the {@link TrackerScheduler}, bridging from Spring Boot's
     * {@link TrackerProperties} to the legacy key-value format.
     */
    Properties buildTrackerProperties()
    {
        Properties p = new Properties();

        // Copy all repository connection properties (host, port, baseUrl, secureComms, batch.count, etc.)
        p.putAll(repositoryProperties);

        // Cron schedules — keyed as trackers expect them
        TrackerProperties.CronConfig cron = props.getCron();
        p.setProperty("alfresco.acl.tracker.cron", cron.getAcl());
        p.setProperty("alfresco.model.tracker.cron", cron.getModel());
        p.setProperty("alfresco.content.tracker.cron", cron.getContent());
        p.setProperty("alfresco.metadata.tracker.cron", cron.getMetadata());
        p.setProperty("alfresco.cascade.tracker.cron", cron.getCascade());
        p.setProperty("alfresco.commit.tracker.cron", cron.getCommit());
        p.setProperty("alfresco.repair.tracker.cron", cron.getRepair());

        // Cascade tracker enabled flag
        p.setProperty("alfresco.cascade.tracker.enabled", String.valueOf(props.isCascadeTrackingEnabled()));

        // Commit interval
        p.setProperty("alfresco.commitInterval", String.valueOf(props.getCommitInterval()));

        return p;
    }

    @PreDestroy
    public void shutdown()
    {
        LOGGER.info("Shutting down tracking subsystem...");
        for (Tracker tracker : trackers)
        {
            try
            {
                if (!tracker.isAlreadyInShutDownMode())
                {
                    tracker.setShutdown(true);
                    tracker.shutdown();
                    LOGGER.info("Tracker {} shut down.", tracker.getClass().getSimpleName());
                }
            }
            catch (Exception e)
            {
                LOGGER.error("Error shutting down tracker {}: {}",
                        tracker.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        if (scheduler != null)
        {
            try
            {
                scheduler.shutdown();
                LOGGER.info("TrackerScheduler shut down.");
            }
            catch (Exception e)
            {
                LOGGER.error("Error shutting down TrackerScheduler: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Loads all models from Solr into the local dictionary, respecting
     * import dependencies. Uses the same topological approach as
     * {@code ModelTracker.loadPersistedModels()}.
     */
    private void loadSolrModelsIntoLocalDictionary(SolrJInformationServer infoSrv)
    {
        List<AlfrescoModel> solrModels = infoSrv.getAlfrescoModels();
        LOGGER.info("Loading {} models from Solr into local dictionary.", solrModels.size());

        // Build a map of namespace URI → M2Model for dependency resolution
        java.util.Map<String, M2Model> modelMap = new java.util.HashMap<>();
        for (AlfrescoModel am : solrModels)
        {
            M2Model model = am.getModel();
            if (model != null)
            {
                for (M2Namespace ns : model.getNamespaces())
                {
                    modelMap.put(ns.getUri(), model);
                }
            }
        }

        // Load in dependency order
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (M2Model model : modelMap.values())
        {
            loadModelWithDeps(modelMap, loaded, model);
        }
        LOGGER.info("Local dictionary: {} models registered.", loaded.size());

        // Call afterInitModels on Solr to refresh CMIS dictionary after all models are loaded
        if (!loaded.isEmpty())
        {
            SolrJModelService modelService = new SolrJModelService(solrClient,
                    props.getSolr().getCollection());
            modelService.afterInitModels();
        }
    }

    private void loadModelWithDeps(java.util.Map<String, M2Model> modelMap,
                                   java.util.Set<String> loaded, M2Model model)
    {
        String name = model.getName();
        if (loaded.contains(name))
        {
            return;
        }
        // Load imports first
        for (M2Namespace imp : model.getImports())
        {
            M2Model dep = modelMap.get(imp.getUri());
            if (dep != null)
            {
                loadModelWithDeps(modelMap, loaded, dep);
            }
        }
        localDictionaryService.putModelOrFail(model);
        loaded.add(name);
    }

    // Visible for testing
    TrackerRegistry getRegistry()
    {
        return registry;
    }

    // Visible for testing
    TrackerScheduler getScheduler()
    {
        return scheduler;
    }

    // Visible for testing
    List<Tracker> getTrackers()
    {
        return trackers;
    }
}
