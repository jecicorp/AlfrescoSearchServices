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
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuditQueryServiceTest
{
    private static final String FOLDER = "workspace://SpacesStore/5f0e-folder";

    private SolrClient solrClient;
    private AuditQueryService service;

    @Before
    public void setUp() throws Exception
    {
        this.solrClient = Mockito.mock(SolrClient.class);
        QueryResponse response = Mockito.mock(QueryResponse.class);
        Mockito.when(response.getResults()).thenReturn(new SolrDocumentList());
        Mockito.when(response.getNextCursorMark()).thenReturn("AoE/Bw==");
        Mockito.when(solrClient.query(Mockito.eq("audit"), Mockito.any(SolrQuery.class))).thenReturn(response);
        this.service = new AuditQueryService(solrClient, "audit");
    }

    private SolrQuery captureQuery() throws Exception
    {
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        Mockito.verify(solrClient).query(Mockito.eq("audit"), captor.capture());
        return captor.getValue();
    }

    private AuditQuery filtered()
    {
        return new AuditQuery(null, null, null, null, null, null, null,
                List.of("abeecher", "GROUP_EVERYONE"), false, null, 50);
    }

    @Test
    public void appliesThePermissionFilterAsAFilterQuery() throws Exception
    {
        service.search(filtered());
        SolrQuery q = captureQuery();

        assertTrue(List.of(q.getFilterQueries()).stream().anyMatch(f -> f.contains("fromIndex=alfresco")));
        assertEquals("READER:(\"abeecher\" OR \"GROUP_EVERYONE\")", q.get("auditReaderQuery"));
    }

    @Test
    public void setsEveryParameterTheFilterDereferences() throws Exception
    {
        service.search(filtered());
        SolrQuery q = captureQuery();

        Map<String, String> params = AuditPermissionFilter
                .build(List.of("abeecher", "GROUP_EVERYONE"), null).params();
        assertEquals(6, params.size());
        params.forEach((name, value) -> assertEquals(value, q.get(name)));
    }

    @Test
    public void carriesAFolderScopeInsideTheJoin() throws Exception
    {
        service.search(new AuditQuery(null, null, null, null, null, null, FOLDER,
                List.of("abeecher"), false, null, 50));
        SolrQuery q = captureQuery();

        assertEquals("ANCESTOR:\"" + FOLDER + "\"", q.get("auditAncestor"));
        assertTrue(q.get("auditScope").contains("filter=$auditAncestor"));
    }

    @Test
    public void sortsByEventTimeThenIdForADeterministicCursor() throws Exception
    {
        service.search(filtered());

        assertEquals("EVENT_TIME desc,id asc", captureQuery().get("sort"));
    }

    @Test
    public void usesACursorRatherThanAnOffset() throws Exception
    {
        service.search(filtered());
        SolrQuery q = captureQuery();

        assertEquals("*", q.get("cursorMark"));
        assertEquals(null, q.get("start"));
    }

    @Test
    public void omitsThePermissionFilterOnlyWhenUnfilteredIsExplicit() throws Exception
    {
        AuditQuery unfiltered = new AuditQuery(null, null, null, null, null, null, null,
                null, true, null, 50);

        service.search(unfiltered);
        SolrQuery q = captureQuery();

        assertEquals(null, q.getFilterQueries());
    }

    @Test
    public void keepsTheFolderScopeOnTheAuditCoreWhenUnfiltered() throws Exception
    {
        service.search(new AuditQuery(null, null, null, null, null, null, FOLDER, null, true, null, 50));

        assertEquals(List.of("ANCESTOR:\"" + FOLDER + "\""), List.of(captureQuery().getFilterQueries()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAQueryThatIsNeitherFilteredNorExplicitlyUnfiltered() throws Exception
    {
        service.search(new AuditQuery(null, null, null, null, null, null, null, null, false, null, 50));
    }

    @Test
    public void turnsEachCriterionIntoItsOwnFilterQuery() throws Exception
    {
        AuditQuery q = new AuditQuery("abeecher", "content-read", null, null, null, null, null,
                List.of("abeecher"), false, null, 50);

        service.search(q);
        List<String> fqs = List.of(captureQuery().getFilterQueries());

        assertTrue(fqs.contains("PRINCIPAL:\"abeecher\""));
        assertTrue(fqs.contains("EVENT_TYPE:\"content-read\""));
    }

    @Test
    public void escapesAValueThatCouldCloseItsOwnQuotedTerm() throws Exception
    {
        service.search(new AuditQuery("ab\"eecher", null, null, "/Company Home/x", null, null, null,
                List.of("abeecher"), false, null, 50));
        List<String> fqs = List.of(captureQuery().getFilterQueries());

        assertTrue(fqs.contains("PRINCIPAL:\"ab\\\"eecher\""));
        assertTrue(fqs.contains("PATH:\\/Company\\ Home\\/x*"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAFacetFieldOutsideTheAllowedSet() throws Exception
    {
        service.facet(filtered(), "PROPERTIES_JSON", 10);
    }
}
