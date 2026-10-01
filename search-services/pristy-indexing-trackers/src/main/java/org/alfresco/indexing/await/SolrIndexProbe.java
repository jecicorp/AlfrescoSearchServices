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

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DBID;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_DOC_TYPE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.FIELD_INTXID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.alfresco.indexing.api.IndexStatusService;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

/**
 * Queries the awaited nodes on the {@code /query} handler of a core, {@value #CHUNK_SIZE} DBIDs per request.
 */
public class SolrIndexProbe implements IndexProbe
{
    static final int CHUNK_SIZE = 500;

    private final SolrClient solrClient;

    /**
     * @param solrClient the client of the Solr server holding the cores
     */
    public SolrIndexProbe(SolrClient solrClient)
    {
        this.solrClient = solrClient;
    }

    @Override
    public Map<Long, List<IndexedDocument>> find(String core, Collection<Long> dbids) throws IOException
    {
        List<Long> all = List.copyOf(dbids);
        Map<Long, List<IndexedDocument>> found = new HashMap<>();
        for (int start = 0; start < all.size(); start += CHUNK_SIZE)
        {
            List<Long> chunk = all.subList(start, Math.min(start + CHUNK_SIZE, all.size()));
            for (SolrDocument document : query(core, chunk))
            {
                Long dbid = IndexStatusService.longValue(document, FIELD_DBID);
                if (dbid == null)
                {
                    continue;
                }
                found.computeIfAbsent(dbid, key -> new ArrayList<>()).add(new IndexedDocument(dbid,
                        IndexStatusService.stringValue(document, FIELD_DOC_TYPE),
                        IndexStatusService.longValue(document, FIELD_INTXID)));
            }
        }
        return found;
    }

    private SolrDocumentList query(String core, List<Long> chunk) throws IOException
    {
        StringJoiner ids = new StringJoiner(" OR ", FIELD_DBID + ":(", ")");
        for (Long dbid : chunk)
        {
            ids.add(Long.toString(dbid));
        }
        SolrQuery query = IndexStatusService.luceneQuery(ids + " AND " + IndexStatusService.NODE_DOCUMENTS);
        query.setRows(chunk.size() * 2);
        query.setFields(FIELD_DBID, FIELD_DOC_TYPE, FIELD_INTXID);
        try
        {
            SolrDocumentList results = solrClient.query(core, query).getResults();
            return results == null ? new SolrDocumentList() : results;
        }
        catch (SolrServerException | RuntimeException e)
        {
            throw new IOException("Index await query failed on core " + core, e);
        }
    }
}
