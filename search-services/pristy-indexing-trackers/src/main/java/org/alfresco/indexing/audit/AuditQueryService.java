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

import org.alfresco.indexing.config.TrackerProperties;
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
    private final List<String> contentCores;
    private final int maxOffset;

    /**
     * @param solrClient the client of the Solr instance holding the audit core
     * @param core       the name of the audit core
     * @param properties the tracker configuration, whose tracked collections are the cores
     *            the permission filter joins against
     * @param maxOffset  the highest offset an offset-paged query may ask for
     */
    public AuditQueryService(
            SolrClient solrClient,
            @Value("${alfresco.tracker.audit.core:audit}") String core,
            TrackerProperties properties,
            @Value("${alfresco.tracker.audit.max-offset:10000}") int maxOffset)
    {
        this.solrClient = solrClient;
        this.core = core;
        this.contentCores = properties.getSolr().getCollections();
        this.maxOffset = maxOffset;
    }

    /**
     * @param query the question
     * @return one page of events, newest first
     * @throws IllegalArgumentException if a cursor is given together with a start above zero,
     *             the two paging modes being exclusive, or if the start is above the
     *             configured maximum offset
     */
    public AuditPage search(AuditQuery query)
    {
        if (query.cursor() != null && query.start() > 0)
        {
            throw new IllegalArgumentException("cursor and start are exclusive; use one or the other");
        }
        if (query.start() > maxOffset)
        {
            throw new IllegalArgumentException("start is above the maximum offset " + maxOffset
                    + "; walk the pages with cursor instead");
        }

        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(query.rows());
        solrQuery.set("sort", "EVENT_TIME desc,id asc");

        if (query.start() > 0)
        {
            solrQuery.setStart(query.start());
        }
        else
        {
            solrQuery.set("cursorMark", query.cursor() == null ? "*" : query.cursor());
        }

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
     * @param field  the field to facet on
     * @param limit  the maximum number of values; zero or less asks for as many as the cap allows
     * @param prefix the prefix every returned value must start with, or null for every value
     * @return each value and how many events carry it
     */
    public Map<String, Long> facet(AuditQuery query, String field, int limit, String prefix)
    {
        if (!FACETABLE.contains(field))
        {
            throw new IllegalArgumentException("not a facetable field: " + field);
        }

        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(field);
        solrQuery.setFacetLimit(limit <= 0 ? MAX_FACET_LIMIT : Math.min(limit, MAX_FACET_LIMIT));
        solrQuery.setFacetMinCount(1);
        if (prefix != null && !prefix.isBlank())
        {
            solrQuery.setFacetPrefix(field, prefix);
        }

        return counts(execute(solrQuery).getFacetField(field));
    }

    /**
     * Who contributed to the folder the query names, and how many times.
     *
     * @param query the question whose folder scopes the count
     * @return the most frequent contributors first, as many as the facet cap allows
     */
    public Map<String, Long> contributors(AuditQuery query)
    {
        SolrQuery solrQuery = baseQuery(query);
        solrQuery.setRows(0);
        solrQuery.addFilterQuery("EVENT_TYPE:(\"" + String.join("\" OR \"", CONTRIBUTING_EVENTS) + "\")");
        solrQuery.setFacet(true);
        solrQuery.addFacetField("PRINCIPAL");
        solrQuery.setFacetLimit(MAX_FACET_LIMIT);
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

        if (query.folder() != null)
        {
            solrQuery.addFilterQuery("ANCESTOR:" + AuditPermissionFilter.quote(query.folder()));
        }

        if (query.unfiltered())
        {
            if (query.authorities() != null && !query.authorities().isEmpty())
            {
                throw new IllegalArgumentException("an unfiltered query carries no authority set");
            }
        }
        else
        {
            AuditPermissionFilter.Filter filter =
                    AuditPermissionFilter.build(query.authorities(), contentCores);
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
