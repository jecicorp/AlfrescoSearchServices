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
package org.alfresco.indexing.server.solrj;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.alfresco.util.cache.DefaultAsynchronouslyRefreshedCacheRegistry;
import org.alfresco.repo.dictionary.CompiledModelsCache;
import org.alfresco.repo.dictionary.DictionaryComponent;
import org.alfresco.repo.dictionary.DictionaryDAOImpl;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.dictionary.NamespaceDAO;
import org.alfresco.repo.i18n.StaticMessageLookup;
import org.alfresco.repo.tenant.SingleTServiceImpl;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.TraceableThreadFactory;
import org.alfresco.util.DynamicallySizedThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local dictionary service that wraps {@link DictionaryDAOImpl} to provide
 * {@link PropertyDefinition} lookups without needing the embedded Solr
 * {@code AlfrescoSolrDataModel} singleton.
 *
 * <p>This is used by the remote tracker module to resolve property definitions
 * from M2 models loaded by the {@code ModelTracker}. The initialization mirrors
 * {@code AlfrescoSolrDataModel}'s constructor.</p>
 */
public class LocalDictionaryService
{
    private static final Logger LOG = LoggerFactory.getLogger(LocalDictionaryService.class);

    private final DictionaryDAOImpl dictionaryDAO;
    private final DictionaryComponent dictionaryComponent;
    private final NamespaceDAO namespaceDAO;
    private final ThreadPoolExecutor threadPool;

    public LocalDictionaryService()
    {
        TenantService tenantService = new SingleTServiceImpl();

        dictionaryDAO = new DictionaryDAOImpl();
        dictionaryDAO.setTenantService(tenantService);

        try
        {
            CompiledModelsCache compiledModelsCache = new CompiledModelsCache();
            compiledModelsCache.setDictionaryDAO(dictionaryDAO);
            compiledModelsCache.setTenantService(tenantService);
            compiledModelsCache.setRegistry(new DefaultAsynchronouslyRefreshedCacheRegistry());

            TraceableThreadFactory threadFactory = new TraceableThreadFactory();
            threadFactory.setThreadDaemon(true);
            threadFactory.setNamePrefix("TrackerDictionary-");

            threadPool = new DynamicallySizedThreadPoolExecutor(
                    2, 2, 120, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), threadFactory,
                    new ThreadPoolExecutor.CallerRunsPolicy());
            compiledModelsCache.setThreadPoolExecutor(threadPool);

            dictionaryDAO.setDictionaryRegistryCache(compiledModelsCache);
            dictionaryDAO.setResourceClassLoader(this.getClass().getClassLoader());
            // Do NOT call dictionaryDAO.init() — built-in models are obsolete.
            // The ModelTracker loads correct models via putModel().
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to initialize LocalDictionaryService", e);
        }

        namespaceDAO = dictionaryDAO;

        dictionaryComponent = new DictionaryComponent();
        dictionaryComponent.setDictionaryDAO(dictionaryDAO);
        dictionaryComponent.setMessageLookup(new StaticMessageLookup());
    }

    /**
     * Registers a model in the local dictionary. Called by the ModelTracker
     * when a model is loaded from the repository.
     *
     * @param model the M2Model to register
     */
    public void putModel(M2Model model)
    {
        try
        {
            dictionaryDAO.putModelIgnoringConstraints(model);
            LOG.debug("Registered model '{}' in local dictionary", model.getName());
        }
        catch (Exception e)
        {
            LOG.debug("Failed to register model '{}' in local dictionary: {}", model.getName(), e.getMessage());
        }
    }

    /**
     * Registers a model in the local dictionary, propagating any exception.
     * Use this when model loading failures are fatal (e.g. during bootstrap).
     *
     * @param model the M2Model to register
     * @throws RuntimeException if the model cannot be compiled
     */
    public void putModelOrFail(M2Model model)
    {
        dictionaryDAO.putModelIgnoringConstraints(model);
        LOG.debug("Registered model '{}' in local dictionary", model.getName());
    }

    /**
     * Returns the property definition for the given QName, or {@code null}
     * if the property is not known to the dictionary.
     */
    public PropertyDefinition getPropertyDefinition(QName propertyQName)
    {
        try
        {
            return dictionaryComponent.getProperty(propertyQName);
        }
        catch (Exception e)
        {
            LOG.trace("Property not found in dictionary: {}", propertyQName);
            return null;
        }
    }

    /**
     * Returns the underlying DictionaryComponent for use as a DictionaryService.
     */
    public DictionaryComponent getDictionaryComponent()
    {
        return dictionaryComponent;
    }

    /**
     * Returns the underlying NamespaceDAO for prefix resolution.
     */
    public NamespaceDAO getNamespaceDAO()
    {
        return namespaceDAO;
    }

    /**
     * Shuts down the internal thread pool.
     */
    public void close()
    {
        threadPool.shutdown();
    }
}
