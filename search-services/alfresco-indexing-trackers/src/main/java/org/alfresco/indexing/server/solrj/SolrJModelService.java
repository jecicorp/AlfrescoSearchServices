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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.AlfrescoModel;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles model-related operations via SolrJ, communicating with the
 * {@code /alfresco/models} request handler on the remote Solr node.
 *
 * <p>Supports the following remote actions: put, list, get, errors, afterinitmodels.</p>
 */
public class SolrJModelService
{
    private static final Logger LOG = LoggerFactory.getLogger(SolrJModelService.class);

    private static final String HANDLER_PATH = "/alfresco/models";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MODEL_QNAME = "modelQName";

    private static final String ACTION_PUT = "put";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_GET = "get";
    private static final String ACTION_ERRORS = "errors";
    private static final String ACTION_AFTER_INIT_MODELS = "afterinitmodels";

    private final SolrClient solrClient;
    private final String collection;

    public SolrJModelService(SolrClient solrClient, String collection)
    {
        this.solrClient = solrClient;
        this.collection = collection;
    }

    /**
     * Pushes a model to Solr via a POST to {@code /alfresco/models?action=put}.
     * The handler registers the model in {@code AlfrescoSolrDataModel} but does
     * NOT call {@code afterInitModels} — the caller must do so explicitly after
     * all models in the batch have been pushed.
     *
     * @param model the M2Model to register
     * @return {@code true} if the server responded with status "ok"
     */
    public boolean putModel(M2Model model)
    {
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            model.toXML(baos);

            ContentStreamBase.ByteArrayStream stream =
                    new ContentStreamBase.ByteArrayStream(baos.toByteArray(), "model.xml");
            stream.setContentType("application/xml");

            ContentStreamUpdateRequest request = new ContentStreamUpdateRequest(HANDLER_PATH);
            request.setParam(PARAM_ACTION, ACTION_PUT);
            request.addContentStream(stream);

            NamedList<Object> response = solrClient.request(request, collection);
            String status = (String) response.get("status");
            if ("ok".equals(status))
            {
                return true;
            }
            LOG.info("Model '{}' deferred by Solr (dependencies may not be loaded yet)", model.getName());
            return false;
        }
        catch (Exception e)
        {
            LOG.warn("Failed to put model '{}' to Solr (will retry): {}", model.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends an explicit {@code afterinitmodels} request to the Solr handler to
     * refresh CMIS dictionary services. Must be called once after all models
     * in the batch have been pushed via {@link #putModel(M2Model)}.
     */
    public void afterInitModels()
    {
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(PARAM_ACTION, ACTION_AFTER_INIT_MODELS);
            params.set("qt", HANDLER_PATH);

            solrClient.query(collection, params);
            LOG.info("afterInitModels completed on Solr");
        }
        catch (Exception e)
        {
            LOG.warn("afterInitModels failed on Solr: {}", e.getMessage());
        }
    }

    /**
     * Removes a model from the Solr-side dictionary.
     * Sends {@code action=remove&modelQName=prefix:localName} to the handler.
     */
    public void removeModel(QName modelQName)
    {
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(PARAM_ACTION, "remove");
            params.set(PARAM_MODEL_QNAME, modelQName.toString());
            params.set("qt", HANDLER_PATH);

            solrClient.query(collection, params);
            LOG.info("Model removed from Solr: {}", modelQName);
        }
        catch (Exception e)
        {
            LOG.debug("Failed to remove model '{}' from Solr: {}", modelQName, e.getMessage());
        }
    }

    /**
     * Retrieves a model by QName from Solr. Sends {@code action=get&modelQName=...}
     * and parses the XML response back to an M2Model.
     *
     * @param modelQName the QName of the model to retrieve
     * @return the deserialized M2Model, or {@code null} if not found or on error
     */
    public M2Model getM2Model(QName modelQName)
    {
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(PARAM_ACTION, ACTION_GET);
            params.set(PARAM_MODEL_QNAME, modelQName.toString());
            params.set("qt", HANDLER_PATH);

            QueryResponse response = solrClient.query(collection, params);
            NamedList<Object> nl = response.getResponse();
            String modelXml = (String) nl.get("modelXml");
            if (modelXml == null)
            {
                return null;
            }
            return M2Model.createModel(new ByteArrayInputStream(modelXml.getBytes("UTF-8")));
        }
        catch (Exception e)
        {
            LOG.warn("Failed to get model '{}' from Solr: {}", modelQName, e.getMessage());
            return null;
        }
    }

    /**
     * Lists all registered Alfresco models from Solr.
     * Sends {@code action=list} and parses the response.
     *
     * @return list of AlfrescoModel instances (model + checksum), or empty list on error
     */
    public List<AlfrescoModel> getAlfrescoModels()
    {
        List<AlfrescoModel> result = new ArrayList<>();
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(PARAM_ACTION, ACTION_LIST);
            params.set("qt", HANDLER_PATH);

            QueryResponse response = solrClient.query(collection, params);
            NamedList<Object> nl = response.getResponse();
            @SuppressWarnings("unchecked")
            NamedList<Object> models = (NamedList<Object>) nl.get("models");
            if (models == null)
            {
                return result;
            }

            for (Map.Entry<String, Object> entry : models)
            {
                @SuppressWarnings("unchecked")
                NamedList<Object> modelEntry = (NamedList<Object>) entry.getValue();
                if (modelEntry == null)
                {
                    continue;
                }
                // Retrieve the full model XML via a separate get call
                String modelNameStr = (String) modelEntry.get("name");
                Object checksumObj = modelEntry.get("checksum");
                Long checksum = checksumObj instanceof Long ? (Long) checksumObj
                        : (checksumObj != null ? Long.valueOf(checksumObj.toString()) : 0L);

                if (modelNameStr != null)
                {
                    // Try to read model XML directly from list response (avoids per-model GET)
                    String modelXml = (String) modelEntry.get("modelXml");
                    M2Model m2Model = null;
                    if (modelXml != null && !modelXml.isEmpty())
                    {
                        try
                        {
                            m2Model = M2Model.createModel(
                                    new ByteArrayInputStream(modelXml.getBytes("UTF-8")));
                        }
                        catch (Exception e)
                        {
                            LOG.warn("Failed to parse model XML for '{}': {}", modelNameStr, e.getMessage());
                        }
                    }
                    if (m2Model != null)
                    {
                        result.add(new AlfrescoModel(m2Model, checksum));
                    }
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to list models from Solr: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Retrieves model registration errors from Solr.
     * Sends {@code action=errors} and parses the response.
     *
     * @return map of model name to set of error strings, or empty map on error
     */
    public Map<String, Set<String>> getModelErrors()
    {
        Map<String, Set<String>> result = new HashMap<>();
        try
        {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(PARAM_ACTION, ACTION_ERRORS);
            params.set("qt", HANDLER_PATH);

            QueryResponse response = solrClient.query(collection, params);
            NamedList<Object> nl = response.getResponse();
            @SuppressWarnings("unchecked")
            NamedList<Object> errors = (NamedList<Object>) nl.get("errors");
            if (errors == null)
            {
                return result;
            }

            for (Map.Entry<String, Object> entry : errors)
            {
                @SuppressWarnings("unchecked")
                Collection<String> errorSet = (Collection<String>) entry.getValue();
                result.put(entry.getKey(),
                        errorSet != null ? new HashSet<>(errorSet) : new HashSet<>());
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to get model errors from Solr: {}", e.getMessage());
        }
        return result;
    }
}
