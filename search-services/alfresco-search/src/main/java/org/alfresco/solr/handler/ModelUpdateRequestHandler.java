/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
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

package org.alfresco.solr.handler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.AlfrescoSolrDataModel;
import org.alfresco.solr.client.AlfrescoModel;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.response.SolrQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solr request handler that receives M2Model updates from remote trackers
 * and registers them in the {@link AlfrescoSolrDataModel} singleton.
 *
 * <p>Supports the following actions via the {@code action} parameter:</p>
 * <ul>
 *   <li>{@code put} - Register or update a model (POST with ContentStream containing M2Model XML)</li>
 *   <li>{@code remove} - Remove a model by QName</li>
 *   <li>{@code list} - List all registered Alfresco models with checksums</li>
 *   <li>{@code get} - Retrieve a model's XML by QName</li>
 *   <li>{@code errors} - Return model registration errors</li>
 *   <li>{@code afterinitmodels} - Refresh CMIS dictionary after a batch of model PUT operations</li>
 * </ul>
 */
public class ModelUpdateRequestHandler extends RequestHandlerBase
{
    private static final Logger LOG = LoggerFactory.getLogger(ModelUpdateRequestHandler.class);

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MODEL_QNAME = "modelQName";

    private static final String ACTION_PUT = "put";
    private static final String ACTION_REMOVE = "remove";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_GET = "get";
    private static final String ACTION_ERRORS = "errors";
    private static final String ACTION_AFTER_INIT_MODELS = "afterinitmodels";

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception
    {
        String action = req.getParams().get(PARAM_ACTION);
        if (action == null || action.isEmpty())
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "Missing required parameter: " + PARAM_ACTION);
        }

        switch (action.toLowerCase())
        {
            case ACTION_PUT:
                handlePut(req, rsp);
                break;
            case ACTION_REMOVE:
                handleRemove(req, rsp);
                break;
            case ACTION_LIST:
                handleList(rsp);
                break;
            case ACTION_GET:
                handleGet(req, rsp);
                break;
            case ACTION_ERRORS:
                handleErrors(rsp);
                break;
            case ACTION_AFTER_INIT_MODELS:
                handleAfterInitModels(rsp);
                break;
            default:
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                        "Unknown action: " + action
                                + ". Supported actions: put, remove, list, get, errors, afterinitmodels");
        }
    }

    /**
     * Reads M2Model XML from the request's ContentStream, registers it in AlfrescoSolrDataModel,
     * and refreshes CMIS dictionary services.
     */
    private void handlePut(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception
    {
        Iterable<ContentStream> streams = req.getContentStreams();
        if (streams == null)
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "PUT action requires a ContentStream with M2Model XML");
        }

        ContentStream stream = streams.iterator().next();
        M2Model model;
        try (InputStream is = stream.getStream())
        {
            model = M2Model.createModel(is);
        }

        AlfrescoSolrDataModel dataModel = AlfrescoSolrDataModel.getInstance();
        boolean success = false;
        try
        {
            success = dataModel.putModel(model);
        }
        catch (Exception e)
        {
            LOG.warn("Model registration deferred (dependencies may not be loaded yet): {} - {}",
                     model.getName(), e.getMessage());
        }

        LOG.info("Model {}: {}", success ? "registered" : "deferred", model.getName());

        rsp.add("status", success ? "ok" : "deferred");
        rsp.add("modelName", model.getName());
    }

    /**
     * Refreshes CMIS dictionary services after all models have been loaded.
     * Must be called once after a batch of PUT operations.
     */
    private void handleAfterInitModels(SolrQueryResponse rsp)
    {
        AlfrescoSolrDataModel.getInstance().afterInitModels();
        LOG.info("afterInitModels completed successfully");
        rsp.add("status", "ok");
    }

    /**
     * Removes a model identified by QName from the AlfrescoSolrDataModel.
     */
    private void handleRemove(SolrQueryRequest req, SolrQueryResponse rsp)
    {
        String qnameStr = req.getParams().get(PARAM_MODEL_QNAME);
        if (qnameStr == null || qnameStr.isEmpty())
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "REMOVE action requires parameter: " + PARAM_MODEL_QNAME);
        }

        QName qname;
        try
        {
            qname = QName.resolveToQName(AlfrescoSolrDataModel.getInstance().getNamespaceDAO(), qnameStr);
        }
        catch (Exception e)
        {
            qname = QName.createQName(qnameStr);
        }
        try
        {
            AlfrescoSolrDataModel.getInstance().removeModel(qname);
            LOG.info("Model removed: {}", qnameStr);
            rsp.add("status", "ok");
        }
        catch (Exception e)
        {
            LOG.debug("Model not found for removal (already absent): {}", qnameStr);
            rsp.add("status", "ok");
        }
    }

    /**
     * Lists all registered Alfresco models with their names and checksums.
     */
    private void handleList(SolrQueryResponse rsp)
    {
        List<AlfrescoModel> models = AlfrescoSolrDataModel.getInstance().getAlfrescoModels();
        NamedList<Object> modelList = new SimpleOrderedMap<>();
        for (AlfrescoModel am : models)
        {
            NamedList<Object> entry = new SimpleOrderedMap<>();
            entry.add("name", am.getModel().getName());
            entry.add("checksum", am.getChecksum());
            // Include model XML so clients don't need a separate GET per model
            try
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                am.getModel().toXML(baos);
                entry.add("modelXml", baos.toString("UTF-8"));
            }
            catch (Exception e)
            {
                LOG.warn("Failed to serialize model {}: {}", am.getModel().getName(), e.getMessage());
            }
            modelList.add(am.getModel().getName(), entry);
        }
        rsp.add("status", "ok");
        rsp.add("models", modelList);
    }

    /**
     * Retrieves a model by QName and serializes it as XML.
     */
    private void handleGet(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception
    {
        String qnameStr = req.getParams().get(PARAM_MODEL_QNAME);
        if (qnameStr == null || qnameStr.isEmpty())
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "GET action requires parameter: " + PARAM_MODEL_QNAME);
        }

        QName qname;
        try
        {
            qname = QName.resolveToQName(AlfrescoSolrDataModel.getInstance().getNamespaceDAO(), qnameStr);
        }
        catch (Exception e)
        {
            // Fallback: try direct QName creation (handles {namespace}localName format)
            qname = QName.createQName(qnameStr);
        }
        M2Model model = AlfrescoSolrDataModel.getInstance().getM2Model(qname);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        model.toXML(baos);

        rsp.add("status", "ok");
        rsp.add("modelName", model.getName());
        rsp.add("modelXml", baos.toString("UTF-8"));
    }

    /**
     * Returns any model registration errors.
     */
    private void handleErrors(SolrQueryResponse rsp)
    {
        Map<String, Set<String>> errors = AlfrescoSolrDataModel.getInstance().getModelErrors();
        NamedList<Object> errorList = new SimpleOrderedMap<>();
        for (Map.Entry<String, Set<String>> entry : errors.entrySet())
        {
            errorList.add(entry.getKey(), entry.getValue());
        }
        rsp.add("status", "ok");
        rsp.add("errors", errorList);
    }

    @Override
    public String getDescription()
    {
        return "Handles Alfresco M2Model updates for remote tracker synchronization";
    }

    @Override
    public PermissionNameProvider.Name getPermissionName(AuthorizationContext request)
    {
        return PermissionNameProvider.Name.READ_PERM;
    }
}
