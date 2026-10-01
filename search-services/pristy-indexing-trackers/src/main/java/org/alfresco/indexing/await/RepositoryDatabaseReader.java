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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.solr.client.GetNodesParameters;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.SOLRAPIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the awaited nodes through the repository tracking API, one DBID range per request.
 */
public class RepositoryDatabaseReader implements DatabaseReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDatabaseReader.class);

    static final int MAX_SPAN = 2000;
    private static final long NO_TX = -1L;

    private final SOLRAPIClient client;
    private final String store;

    /**
     * @param client the repository tracking client
     * @param store  the store a live node must belong to, e.g. {@code workspace://SpacesStore}
     */
    public RepositoryDatabaseReader(SOLRAPIClient client, String store)
    {
        this.client = client;
        this.store = store;
    }

    @Override
    public Map<Long, DatabaseNode> read(Collection<Long> dbids)
    {
        List<Long> sorted = new ArrayList<>(new TreeSet<>(dbids));
        Map<Long, DatabaseNode> read = new HashMap<>();
        int start = 0;
        while (start < sorted.size())
        {
            int end = start;
            while (end + 1 < sorted.size() && sorted.get(end + 1) - sorted.get(start) < MAX_SPAN)
            {
                end++;
            }
            readRange(sorted.subList(start, end + 1), read);
            start = end + 1;
        }
        return read;
    }

    private void readRange(List<Long> range, Map<Long, DatabaseNode> read)
    {
        long from = range.get(0);
        long to = range.get(range.size() - 1);
        GetNodesParameters parameters = new GetNodesParameters();
        parameters.setFromNodeId(from);
        parameters.setToNodeId(to);
        Map<Long, Node> nodes = new HashMap<>();
        try
        {
            for (Node node : client.getNodes(parameters, (int) (to - from + 1)))
            {
                nodes.put(node.getId(), node);
            }
        }
        catch (IOException | AuthenticationException | RuntimeException e)
        {
            LOGGER.warn("Could not read nodes {} to {} from the repository", from, to, e);
            for (Long dbid : range)
            {
                read.put(dbid, new DatabaseNode(dbid, DatabaseNode.Status.UNREACHABLE, NO_TX));
            }
            return;
        }
        for (Long dbid : range)
        {
            read.put(dbid, classify(dbid, nodes.get(dbid)));
        }
    }

    private DatabaseNode classify(long dbid, Node node)
    {
        if (node == null)
        {
            return new DatabaseNode(dbid, DatabaseNode.Status.ORPHAN, NO_TX);
        }
        SolrApiNodeStatus status = node.getStatus();
        boolean updated = status == SolrApiNodeStatus.UPDATED || status == SolrApiNodeStatus.NON_SHARD_UPDATED;
        boolean inStore = node.getNodeRef() == null || node.getNodeRef().startsWith(store + "/");
        if (!updated || !inStore)
        {
            return new DatabaseNode(dbid, DatabaseNode.Status.ORPHAN, NO_TX);
        }
        return new DatabaseNode(dbid, DatabaseNode.Status.LIVE, node.getTxnId());
    }
}
