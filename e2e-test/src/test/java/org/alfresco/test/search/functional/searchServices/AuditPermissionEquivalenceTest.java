/*-
 * #%L
 * Pristy Search Services E2E Tests
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
package org.alfresco.test.search.functional.searchServices;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class AuditPermissionEquivalenceTest
{
    private static final String SOLR = System.getProperty("solr.url", "http://localhost:8983/solr");
    private static final String SECRET = System.getProperty("solr.secret", "secret");
    private static final Pattern NUM_FOUND = Pattern.compile("\"numFound\":(\\d+)");

    private static long count(String query, String... filters) throws Exception
    {
        StringBuilder url = new StringBuilder(SOLR).append("/alfresco/select?rows=0&omitHeader=true&defType=lucene");
        url.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        for (String f : filters)
        {
            url.append("&fq=").append(URLEncoder.encode(f, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("X-Alfresco-Search-Secret", SECRET)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200, "Solr answered " + response.body());
        Matcher m = NUM_FOUND.matcher(response.body());
        assertTrue(m.find(), "no numFound in " + response.body());
        return Long.parseLong(m.group(1));
    }

    private static String plainSolr(List<String> authorities)
    {
        String quoted = "\\\"" + String.join("\\\" OR \\\"", authorities) + "\\\"";
        return "+DOC_TYPE:Node"
                + " +_query_:\"{!join from=ACLID to=ACLID}READER:(" + quoted + ")\""
                + " -_query_:\"{!join from=ACLID to=ACLID}DENIED:(" + quoted + ")\"";
    }

    private static String native_(List<String> authorities)
    {
        return "{!afts}|AUTHSET:\":" + String.join(":", authorities) + "\"";
    }

    @DataProvider(name = "authoritySets")
    public Object[][] authoritySets()
    {
        return new Object[][] {
                { List.of("GROUP_site_swsdp_SiteConsumer") },
                { List.of("abeecher") },
                { List.of("abeecher", "mjackson", "GROUP_site_swsdp_SiteConsumer") },
                { List.of("GROUP_EVERYONE") },
        };
    }

    @Test(dataProvider = "authoritySets")
    public void plainSolrFilterMatchesAftsFilter(List<String> authorities) throws Exception
    {
        long plain = count(plainSolr(authorities));
        long alfresco = count("*:*", "DOC_TYPE:Node", native_(authorities));

        assertEquals(plain, alfresco,
                "plain-Solr and AFTS permission filters disagree for " + authorities);
    }

    @Test
    public void theFilterIsSelective() throws Exception
    {
        long all = count("*:*", "DOC_TYPE:Node");
        long restricted = count(plainSolr(List.of("GROUP_site_swsdp_SiteConsumer")));

        assertTrue(restricted < all,
                "the filter returned every node, so it is not filtering at all");
    }
}
