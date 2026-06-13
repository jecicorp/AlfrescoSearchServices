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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.TenantDbId;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Repair strategy for nodes that have no properties (empty nodes).
 * <p>
 * An empty node is either:
 * - A node that has been deleted from the repository (metadata not found)
 * - A node that legitimately has no properties
 * <p>
 * If the node is not found, it is purged from Solr. If it exists but has
 * no properties, it is considered legitimately empty and the repair succeeds
 * without action.
 */
public class EmptyNodeStrategy implements RepairStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(EmptyNodeStrategy.class);
    private static final String CATEGORY = "EMPTY_NODE";

    private final SOLRAPIClient solrApiClient;
    private final InformationServer infoSrv;

    public EmptyNodeStrategy(SOLRAPIClient solrApiClient, InformationServer infoSrv)
    {
        this.solrApiClient = solrApiClient;
        this.infoSrv = infoSrv;
    }

    @Override
    public String category()
    {
        return CATEGORY;
    }

    @Override
    public RepairResult tryRepair(SolrDocument doc, TenantDbId docRef)
    {
        try
        {
            NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
            nmdp.setNodeIds(List.of(docRef.dbId));
            nmdp.setMaxResults(1);

            List<NodeMetaData> metaDataList = solrApiClient.getNodesMetaData(nmdp);
            if (metaDataList == null || metaDataList.isEmpty())
            {
                // Node not found in repository — purge from Solr
                infoSrv.deleteByNodeId(docRef.dbId);
                LOG.info("Purged node {} — not found in repository", docRef.dbId);
                return new RepairResult(docRef.dbId, true, CATEGORY,
                        "Node not found in repository; purged from Solr");
            }

            NodeMetaData nmd = metaDataList.get(0);
            Map<?, ? extends PropertyValue> properties = nmd.getProperties();
            if (properties == null || properties.isEmpty())
            {
                // Node exists but has no properties — legitimately empty
                LOG.info("Node {} is legitimately empty", docRef.dbId);
                return new RepairResult(docRef.dbId, true, CATEGORY,
                        "Node is legitimately empty");
            }

            // Node has properties — not our concern
            return null;
        }
        catch (Exception e)
        {
            LOG.warn("Error during repair of node {}: {}", docRef.dbId, e.getMessage(), e);
            return new RepairResult(docRef.dbId, false, CATEGORY,
                    "Error: " + e.getMessage());
        }
    }
}
