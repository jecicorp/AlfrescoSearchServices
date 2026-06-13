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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private RepairTracker repairTracker;
    private final Map<String, SolrJInformationServer> informationServers = new LinkedHashMap<>();
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
        List<String> collections = props.getSolr().getCollections();
        Properties trackerProps = buildTrackerProperties();

        logEffectiveConfiguration(collections);

        LOGGER.info("Initialising tracking subsystem for collections: {}", collections);

        // Shared registry and scheduler across all cores
        registry = new TrackerRegistry();
        String firstCore = collections.get(0);
        scheduler = new TrackerScheduler(firstCore);

        // ModelTracker is shared (one per repo, not per core) — init on first core
        SolrJInformationServer firstInfoSrv = initInformationServer(firstCore, trackerProps);
        String solrHome = props.getSolrHome();

        SolrJModelService modelService = new SolrJModelService(solrClient, firstCore);
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

        ModelTracker modelTracker = new ModelTracker(
                solrHome, trackerProps, repoClient, firstCore, firstInfoSrv, dataModelCallback);
        registry.setModelTracker(modelTracker);

        LOGGER.info("ModelTracker: ensuring first model sync.");
        modelTracker.ensureFirstModelSync();
        loadSolrModelsIntoLocalDictionary(firstInfoSrv);
        scheduler.schedule(modelTracker, firstCore, trackerProps);
        LOGGER.info("ModelTracker initialised and scheduled.");

        // Init trackers for each core
        for (String coreName : collections)
        {
            SolrJInformationServer infoSrv = coreName.equals(firstCore)
                    ? firstInfoSrv
                    : initInformationServer(coreName, trackerProps);

            initCoreTrackers(coreName, trackerProps, infoSrv);
        }

        LOGGER.info("Tracking subsystem fully initialised for {} collections, {} trackers active.",
                collections.size(), trackers.size() + 1 /* +1 for ModelTracker */);
    }

    /**
     * Logs the cron schedules and timing settings actually in effect, so the
     * resolved configuration (defaults + environment overrides) is visible in
     * the logs at startup. See docs/tracker-configuration.md for impacts.
     */
    private void logEffectiveConfiguration(List<String> collections)
    {
        TrackerProperties.CronConfig cron = props.getCron();
        LOGGER.info("Tracker configuration in effect (collections={}):", collections);
        LOGGER.info("  cron.metadata = {}", cron.getMetadata());
        LOGGER.info("  cron.acl      = {}", cron.getAcl());
        LOGGER.info("  cron.content  = {}", cron.getContent());
        LOGGER.info("  cron.commit   = {}", cron.getCommit());
        LOGGER.info("  cron.model    = {}", cron.getModel());
        LOGGER.info("  cron.cascade  = {} (enabled={})", cron.getCascade(), props.isCascadeTrackingEnabled());
        LOGGER.info("  cron.repair   = {} (maxRetries={})", cron.getRepair(), props.getRepairMaxRetries());
        LOGGER.info("  commitInterval={} ms, newSearcherInterval={} ms, batchCount={}",
                props.getCommitInterval(), props.getNewSearcherInterval(), props.getBatchCount());
    }

    private SolrJInformationServer initInformationServer(String coreName, Properties trackerProps)
    {
        SolrJModelService modelService = new SolrJModelService(solrClient, coreName);
        DataModelCallback callback = new DataModelCallback()
        {
            @Override
            public void afterInitModels() { modelService.afterInitModels(); }

            @Override
            public void removeModel(QName modelName) { modelService.removeModel(modelName); }
        };

        SolrJInformationServer infoSrv = new SolrJInformationServer(
                solrClient, coreName, trackerProps, callback, repoClient, localDictionaryService);
        infoSrv.setNamespaceDAO(localNamespaceDAO);
        infoSrv.setTrackerRegistry(registry);
        informationServers.put(coreName, infoSrv);
        return infoSrv;
    }

    private void initCoreTrackers(String coreName, Properties trackerProps, SolrJInformationServer infoSrv)
    {
        AclTracker aclTracker = new AclTracker(trackerProps, repoClient, coreName, infoSrv);
        registry.register(coreName, aclTracker);
        scheduler.schedule(aclTracker, coreName, trackerProps);

        ContentTracker contentTracker = new ContentTracker(trackerProps, repoClient, coreName, infoSrv);
        registry.register(coreName, contentTracker);
        scheduler.schedule(contentTracker, coreName, trackerProps);

        MetadataTracker metadataTracker = new MetadataTracker(trackerProps, repoClient, coreName, infoSrv, true);
        registry.register(coreName, metadataTracker);
        scheduler.schedule(metadataTracker, coreName, trackerProps);

        List<Tracker> coreTrackers = new ArrayList<>(Arrays.asList(contentTracker, metadataTracker, aclTracker));

        if (props.isCascadeTrackingEnabled())
        {
            CascadeTracker cascadeTracker = new CascadeTracker(trackerProps, repoClient, coreName, infoSrv);
            registry.register(coreName, cascadeTracker);
            scheduler.schedule(cascadeTracker, coreName, trackerProps);
            coreTrackers.add(0, cascadeTracker);
        }

        CommitTracker commitTracker = new CommitTracker(trackerProps, repoClient, coreName, infoSrv, coreTrackers);
        registry.register(coreName, commitTracker);
        scheduler.schedule(commitTracker, coreName, trackerProps);

        List<RepairStrategy> repairStrategies = List.of(
                new UnresolvedModelStrategy(repoClient, infoSrv, localDictionaryService),
                new EmptyNodeStrategy(repoClient, infoSrv)
        );
        RepairTracker coreRepairTracker = new RepairTracker(
                trackerProps, repoClient, coreName, infoSrv,
                repairStrategies, registry, props.getRepairMaxRetries());
        registry.register(coreName, coreRepairTracker);
        scheduler.schedule(coreRepairTracker, coreName, trackerProps);

        // Keep the first core's repair tracker for the actuator endpoint
        if (repairTracker == null)
        {
            repairTracker = coreRepairTracker;
        }

        trackers.addAll(coreTrackers);
        trackers.add(commitTracker);
        trackers.add(coreRepairTracker);

        LOGGER.info("Core '{}': {} trackers registered and scheduled.", coreName, coreTrackers.size() + 2);
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

        // Commit interval and searcher refresh interval
        p.setProperty("alfresco.commitInterval", String.valueOf(props.getCommitInterval()));
        p.setProperty("alfresco.newSearcherInterval", String.valueOf(props.getNewSearcherInterval()));

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
            SolrJModelService ms = new SolrJModelService(solrClient,
                    props.getSolr().getCollections().get(0));
            ms.afterInitModels();
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

    public TrackerRegistry getRegistry()
    {
        return registry;
    }

    /** Returns the InformationServer for the given core, or the first one if core is null. */
    public SolrJInformationServer getInformationServer(String coreName)
    {
        if (coreName != null && informationServers.containsKey(coreName))
        {
            return informationServers.get(coreName);
        }
        return informationServers.values().iterator().next();
    }

    /** Returns all InformationServers keyed by core name. */
    public Map<String, SolrJInformationServer> getInformationServers()
    {
        return informationServers;
    }

    /** @deprecated Use {@link #getInformationServer(String)} instead. */
    @Deprecated
    public SolrJInformationServer getInformationServer()
    {
        return informationServers.values().iterator().next();
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

    /** Returns the RepairTracker instance, or null if not yet initialised. */
    public RepairTracker getRepairTracker()
    {
        return repairTracker;
    }
}
