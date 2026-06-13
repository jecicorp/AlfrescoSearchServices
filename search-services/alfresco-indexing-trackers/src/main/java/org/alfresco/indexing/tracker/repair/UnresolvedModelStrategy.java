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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.server.solrj.LocalDictionaryService;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repair strategy for nodes that were indexed with unresolved model properties.
 * <p>
 * When a node is first indexed, some properties may lack a {@code PropertyDefinition}
 * in the local dictionary (e.g., a custom model not yet loaded). This strategy
 * re-checks the dictionary and re-indexes the node if all properties now resolve.
 */
public class UnresolvedModelStrategy implements RepairStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(UnresolvedModelStrategy.class);
    private static final String CATEGORY = "UNRESOLVED_MODEL";

    private final SOLRAPIClient solrApiClient;
    private final InformationServer infoSrv;
    private final LocalDictionaryService dictionaryService;

    public UnresolvedModelStrategy(SOLRAPIClient solrApiClient,
                                   InformationServer infoSrv,
                                   LocalDictionaryService dictionaryService)
    {
        this.solrApiClient = solrApiClient;
        this.infoSrv = infoSrv;
        this.dictionaryService = dictionaryService;
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
                return null;
            }

            NodeMetaData nmd = metaDataList.get(0);
            Map<QName, PropertyValue> properties = nmd.getProperties();
            if (properties == null || properties.isEmpty())
            {
                return null;
            }

            Set<QName> unresolved = properties.keySet().stream()
                    .filter(qname -> dictionaryService.getPropertyDefinition(qname) == null)
                    .collect(Collectors.toSet());

            if (unresolved.isEmpty())
            {
                Node node = new Node();
                node.setId(nmd.getId());
                node.setTxnId(nmd.getTxnId());
                node.setStatus(SolrApiNodeStatus.UPDATED);

                infoSrv.indexNode(node, true);

                LOG.info("Re-indexed node {} — all properties now resolve", docRef.dbId);
                return new RepairResult(docRef.dbId, true, CATEGORY,
                        "All properties now resolve; node re-indexed");
            }
            else
            {
                String namespaces = unresolved.stream()
                        .map(QName::getNamespaceURI)
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(", "));

                LOG.debug("Node {} still has unresolved properties in namespaces: {}",
                        docRef.dbId, namespaces);
                return new RepairResult(docRef.dbId, false, CATEGORY,
                        "Properties still unresolved in namespaces: " + namespaces);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Error during repair of node {}: {}", docRef.dbId, e.getMessage(), e);
            return new RepairResult(docRef.dbId, false, CATEGORY,
                    "Error: " + e.getMessage());
        }
    }
}
