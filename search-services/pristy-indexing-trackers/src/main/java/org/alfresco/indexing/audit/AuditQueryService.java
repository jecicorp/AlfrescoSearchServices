/*-
 * #%L
 * Pristy Indexing Trackers
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
package org.alfresco.indexing.audit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs audit questions against the audit core. */
@Service
@ConditionalOnProperty(prefix = "alfresco.tracker.audit", name = "enabled", havingValue = "true")
public class AuditQueryService
{
    private static final List<String> CONTRIBUTING_EVENTS = List.of(
            "create-node", "content-update", "update-properties", "create-version", "check-in");

    private static final int MAX_FACET_LIMIT = 1000;

    private static final Set<String> FACETABLE = Set.of(
            "PRINCIPAL", "EVENT_TYPE", "PATH", "SITE_ID", "NODE_TYPE", "MIMETYPE");

    private final SolrClient solrClient;
    private final String core;

    public AuditQueryService(SolrClient solrClient, @Value("${alfresco.tracker.audit.core:audit}") String core)
    {
        this.solrClient = solrClient;
        this.core = core;
    }

    /**
     * @param query the question
     * @return one page of events, newest first
     */
    public AuditPage search(AuditQuery query)
    {
        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(query.rows());
        solrQuery.set("sort", "EVENT_TIME desc,id asc");
        solrQuery.set("cursorMark", query.cursor() == null ? "*" : query.cursor());

        QueryResponse response = execute(solrQuery);

        List<AuditEvent> events = new ArrayList<>();
        for (SolrDocument doc : response.getResults())
        {
            events.add(new AuditEvent(
                    str(doc, "id"), str(doc, "EVENT_TIME"), str(doc, "EVENT_TYPE"), str(doc, "PRINCIPAL"),
                    str(doc, "NODE_UUID"), str(doc, "NODE_NAME"), str(doc, "PATH"), str(doc, "PROPERTIES_JSON")));
        }
        return new AuditPage(events, response.getResults().getNumFound(), response.getNextCursorMark());
    }

    /**
     * @param field the field to facet on
     * @param limit the maximum number of values
     * @return each value and how many events carry it
     */
    public Map<String, Long> facet(AuditQuery query, String field, int limit)
    {
        if (!FACETABLE.contains(field))
        {
            throw new IllegalArgumentException("not a facetable field: " + field);
        }

        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(field);
        solrQuery.setFacetLimit(Math.min(Math.max(limit, 1), MAX_FACET_LIMIT));
        solrQuery.setFacetMinCount(1);

        return counts(execute(solrQuery).getFacetField(field));
    }

    /** Who contributed to the folder the query names, and how many times. */
    public Map<String, Long> contributors(AuditQuery query)
    {
        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(0);
        solrQuery.addFilterQuery("EVENT_TYPE:(\"" + String.join("\" OR \"", CONTRIBUTING_EVENTS) + "\")");
        solrQuery.setFacet(true);
        solrQuery.addFacetField("PRINCIPAL");
        solrQuery.setFacetLimit(-1);
        solrQuery.setFacetMinCount(1);

        return counts(execute(solrQuery).getFacetField("PRINCIPAL"));
    }

    private SolrQuery baseQuery(AuditQuery query)
    {
        SolrQuery solrQuery = new SolrQuery("*:*");
        solrQuery.set("defType", "lucene");

        if (query.folder() != null && query.folder().isBlank())
        {
            throw new IllegalArgumentException("folder is blank; omit it for the whole repository");
        }

        if (query.pathPrefix() != null && query.pathPrefix().isBlank())
        {
            throw new IllegalArgumentException("pathPrefix is blank; omit it for no path restriction");
        }

        if (query.unfiltered())
        {
            if (query.authorities() != null && !query.authorities().isEmpty())
            {
                throw new IllegalArgumentException("an unfiltered query carries no authority set");
            }
            if (query.folder() != null)
            {
                solrQuery.addFilterQuery("ANCESTOR:" + AuditPermissionFilter.quote(query.folder()));
            }
        }
        else
        {
            AuditPermissionFilter.Filter filter = AuditPermissionFilter.build(query.authorities(), query.folder());
            solrQuery.addFilterQuery(filter.fq());
            filter.params().forEach(solrQuery::set);
        }

        if (query.principal() != null) { solrQuery.addFilterQuery("PRINCIPAL:" + AuditPermissionFilter.quote(query.principal())); }
        if (query.eventType() != null) { solrQuery.addFilterQuery("EVENT_TYPE:" + AuditPermissionFilter.quote(query.eventType())); }
        if (query.nodeUuid() != null) { solrQuery.addFilterQuery("NODE_UUID:" + AuditPermissionFilter.quote(query.nodeUuid())); }
        if (query.pathPrefix() != null) { solrQuery.addFilterQuery("PATH:" + ClientUtils.escapeQueryChars(query.pathPrefix()) + "*"); }
        if (query.fromMs() != null || query.toMs() != null)
        {
            solrQuery.addFilterQuery("EVENT_TIME:[" + bound(query.fromMs()) + " TO " + bound(query.toMs()) + "]");
        }
        return solrQuery;
    }

    private QueryResponse execute(SolrQuery solrQuery)
    {
        try
        {
            return solrClient.query(core, solrQuery);
        }
        catch (SolrException e)
        {
            if (e.code() == 400)
            {
                throw new IllegalArgumentException("Audit query refused: " + e.getMessage(), e);
            }
            throw new AuditException("Audit query failed", e);
        }
        catch (Exception e)
        {
            throw new AuditException("Audit query failed", e);
        }
    }

    private static Map<String, Long> counts(FacetField field)
    {
        Map<String, Long> out = new LinkedHashMap<>();
        if (field != null && field.getValues() != null)
        {
            field.getValues().forEach(c -> out.put(c.getName(), c.getCount()));
        }
        return out;
    }

    private static String bound(Long millis)
    {
        return millis == null ? "*" : Instant.ofEpochMilli(millis).toString();
    }

    private static String str(SolrDocument doc, String field)
    {
        Object value = doc.getFieldValue(field);
        if (value instanceof Date date)
        {
            return date.toInstant().toString();
        }
        return value == null ? null : value.toString();
    }
}
