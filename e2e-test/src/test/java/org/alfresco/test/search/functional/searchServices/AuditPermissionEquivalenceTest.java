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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Pins the load-bearing claim of the audit design: the plain-Solr permission filter
 * selects exactly the nodes Alfresco's own {@code +(AUTHSET) -(DENYSET)} selects.
 */
public class AuditPermissionEquivalenceTest
{
    private static final Properties E2E = loadE2eProperties();
    private static final String SOLR = E2E.getProperty("solr.scheme", "http") + "://"
            + E2E.getProperty("solr.server", "localhost") + ":"
            + E2E.getProperty("solr.port", "8083") + "/solr";
    private static final String SECRET = E2E.getProperty("solr.secret", "secret");
    private static final String ADMIN = E2E.getProperty("admin.user", "admin");
    private static final String SCOPE = "{!bool filter=$auditType must=$auditReader must_not=$auditDenied}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Properties loadE2eProperties()
    {
        Properties properties = new Properties();
        try (InputStream in = AuditPermissionEquivalenceTest.class.getResourceAsStream("/default.properties"))
        {
            if (in != null)
            {
                properties.load(in);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        properties.putAll(System.getProperties());
        return properties;
    }

    private static JsonNode query(String... nameValuePairs) throws Exception
    {
        StringBuilder url = new StringBuilder(SOLR)
                .append("/alfresco/select?rows=0&omitHeader=true&defType=lucene");
        for (int i = 0; i < nameValuePairs.length; i += 2)
        {
            url.append('&').append(nameValuePairs[i]).append('=')
                    .append(URLEncoder.encode(nameValuePairs[i + 1], StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("X-Alfresco-Search-Secret", SECRET)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200, "Solr answered " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static long count(String... nameValuePairs) throws Exception
    {
        return query(nameValuePairs).path("response").path("numFound").asLong();
    }

    private static String[] plainSolrFilter(List<String> authorities)
    {
        String quoted = authorities.stream()
                .map(AuditPermissionEquivalenceTest::quote)
                .collect(Collectors.joining(" OR "));

        return new String[] {
                "q", SCOPE,
                "auditType", "DOC_TYPE:Node",
                "auditReaderQuery", "READER:(" + quoted + ")",
                "auditDeniedQuery", "DENIED:(" + quoted + ")",
                "auditReader", "{!join from=ACLID to=ACLID v=$auditReaderQuery}",
                "auditDenied", "{!join from=ACLID to=ACLID v=$auditDeniedQuery}" };
    }

    private static String quote(String value)
    {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String aftsFilter(List<String> authorities)
    {
        return "{!afts}|AUTHSET:\":" + String.join(":", authorities) + "\"";
    }

    private static List<String> readersInTheRepository() throws Exception
    {
        JsonNode values = query("q", "{!join from=ACLID to=ACLID}DOC_TYPE:Node", "fq", "DOC_TYPE:Acl",
                "facet", "true", "facet.field", "READER",
                "facet.limit", "40", "facet.mincount", "1")
                .path("facet_counts").path("facet_fields").path("READER");

        List<String> authorities = new ArrayList<>();
        for (int i = 0; i < values.size(); i += 2)
        {
            String authority = values.get(i).asText();
            if (!authority.startsWith("ROLE_") && !authority.equalsIgnoreCase(ADMIN))
            {
                authorities.add(authority);
            }
        }
        return authorities;
    }

    @DataProvider(name = "authoritySets")
    public Object[][] authoritySets() throws Exception
    {
        List<String> readers = readersInTheRepository();
        List<Object[]> sets = new ArrayList<>();
        for (int i = 0; i < Math.min(3, readers.size()); i++)
        {
            sets.add(new Object[] { List.of(readers.get(i)) });
        }
        if (readers.size() >= 2)
        {
            sets.add(new Object[] { List.copyOf(readers.subList(0, Math.min(5, readers.size()))) });
        }
        return sets.toArray(new Object[0][]);
    }

    @Test
    public void theRepositoryDeclaresEnoughAuthoritiesToCompareOn() throws Exception
    {
        List<String> readers = readersInTheRepository();

        assertTrue(readers.size() >= 3,
                "the repository declares too few ordinary authorities to compare on: " + readers);
    }

    @Test(dataProvider = "authoritySets")
    public void plainSolrFilterMatchesAftsFilter(List<String> authorities) throws Exception
    {
        long plain = count(plainSolrFilter(authorities));
        long afts = count("q", "*:*", "fq", "DOC_TYPE:Node", "fq", aftsFilter(authorities));

        assertTrue(plain > 0, "no node is readable by " + authorities
                + ", so the comparison proves nothing");
        assertEquals(plain, afts,
                "plain-Solr and AFTS permission filters disagree for " + authorities);
    }

    @Test
    public void theFilterIsSelective() throws Exception
    {
        List<String> readers = readersInTheRepository();
        long all = count("q", "*:*", "fq", "DOC_TYPE:Node");
        long restricted = count(plainSolrFilter(List.of(readers.get(readers.size() - 1))));

        assertTrue(all > 0, "the alfresco core holds no node document");
        assertTrue(restricted > 0, "the filter returned nothing at all");
        assertTrue(restricted < all,
                "the filter returned every node, so it is not filtering at all");
    }

    @DataProvider(name = "privilegedAuthorities")
    public Object[][] privilegedAuthorities()
    {
        return new Object[][] { { "ROLE_OWNER" }, { ADMIN } };
    }

    @Test(dataProvider = "privilegedAuthorities")
    public void aPrivilegedAuthorityIsNotComparable(String authority) throws Exception
    {
        long plain = count(plainSolrFilter(List.of(authority)));
        long afts = count("q", "*:*", "fq", "DOC_TYPE:Node", "fq", aftsFilter(List.of(authority)));

        assertTrue(plain > 0,
                "the plain-Solr filter returned nothing for " + authority + ", so it is broken");
        assertTrue(afts > plain, "AFTS no longer widens the privileged authority " + authority);
    }

    @Test
    public void onePrivilegedAuthorityWidensTheWholeSet() throws Exception
    {
        List<String> ordinary = List.of(readersInTheRepository().get(0));
        List<String> mixed = List.of(ordinary.get(0), ADMIN);

        long plain = count(plainSolrFilter(mixed));
        long afts = count("q", "*:*", "fq", "DOC_TYPE:Node", "fq", aftsFilter(mixed));
        long ordinaryAfts = count("q", "*:*", "fq", "DOC_TYPE:Node", "fq", aftsFilter(ordinary));

        assertTrue(afts > ordinaryAfts,
                "AFTS no longer widens a set holding one privileged authority");
        assertTrue(afts > plain,
                "the two filters agree on a set holding a privileged authority, which they did not");
    }

    private static final String PROBE_IN = "audit-probe-readable";
    private static final String PROBE_OUT = "audit-probe-unreadable";
    private static final long UNKNOWN_DBID = 987654321098L;

    private static JsonNode send(HttpRequest.Builder builder) throws Exception
    {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                builder.header("X-Alfresco-Search-Secret", SECRET).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(response.statusCode(), 200, "Solr answered " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static JsonNode select(String core, String... nameValuePairs) throws Exception
    {
        StringBuilder url = new StringBuilder(SOLR).append('/').append(core)
                .append("/select?omitHeader=true&defType=lucene");
        for (int i = 0; i < nameValuePairs.length; i += 2)
        {
            url.append('&').append(nameValuePairs[i]).append('=')
                    .append(URLEncoder.encode(nameValuePairs[i + 1], StandardCharsets.UTF_8));
        }
        return send(HttpRequest.newBuilder(URI.create(url.toString())).GET());
    }

    private static void updateAudit(String json) throws Exception
    {
        send(HttpRequest.newBuilder(URI.create(SOLR + "/audit/update?commit=true"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private static String productionFilter()
    {
        return "{!lucene}"
                + "_query_:\"{!join fromIndex=alfresco from=DBID to=NODE_DBID v=$auditScope}\""
                + " OR "
                + "_query_:\"{!join fromIndex=archive from=DBID to=NODE_DBID v=$auditScope}\"";
    }

    private static List<String> productionParams(List<String> authorities)
    {
        String quoted = authorities.stream()
                .map(AuditPermissionEquivalenceTest::quote)
                .collect(Collectors.joining(" OR "));
        return List.of(
                "auditType", "DOC_TYPE:Node",
                "auditReaderQuery", "READER:(" + quoted + ")",
                "auditDeniedQuery", "DENIED:(" + quoted + ")",
                "auditReader", "{!join from=ACLID to=ACLID v=$auditReaderQuery}",
                "auditDenied", "{!join from=ACLID to=ACLID v=$auditDeniedQuery}",
                "auditScope", "{!bool filter=$auditType must=$auditReader must_not=$auditDenied}");
    }

    private static Set<String> probeIds(JsonNode response)
    {
        Set<String> ids = new HashSet<>();
        for (JsonNode doc : response.path("response").path("docs"))
        {
            ids.add(doc.path("id").asText());
        }
        return ids;
    }

    @Test
    public void theAuditCoreJoinsNodeDbidToDbidThroughBothCores() throws Exception
    {
        List<String> authorities = List.of(readersInTheRepository().get(0));
        String[] readable = plainSolrFilter(authorities);
        String[] withFields = new String[readable.length + 4];
        System.arraycopy(readable, 0, withFields, 0, readable.length);
        withFields[readable.length] = "fl";
        withFields[readable.length + 1] = "DBID";
        withFields[readable.length + 2] = "rows";
        withFields[readable.length + 3] = "1";
        JsonNode node = select("alfresco", withFields).path("response").path("docs");
        assertTrue(node.size() == 1, "no node is readable by " + authorities);
        long readableDbid = node.get(0).path("DBID").asLong();

        assertEquals(select("alfresco", "q", "DBID:" + UNKNOWN_DBID, "rows", "0")
                .path("response").path("numFound").asLong(), 0L, "the unknown DBID is taken");
        assertEquals(select("archive", "q", "DBID:" + UNKNOWN_DBID, "rows", "0")
                .path("response").path("numFound").asLong(), 0L, "the unknown DBID is taken in archive");

        try
        {
            updateAudit("[{\"id\":\"" + PROBE_IN + "\",\"NODE_DBID\":" + readableDbid + "},"
                    + "{\"id\":\"" + PROBE_OUT + "\",\"NODE_DBID\":" + UNKNOWN_DBID + "}]");

            String[] probes = { "q", "id:audit-probe-*", "fl", "id", "rows", "10" };
            assertEquals(probeIds(select("audit", probes)), Set.of(PROBE_IN, PROBE_OUT),
                    "the probes were not indexed");

            List<String> request = new ArrayList<>(List.of(probes));
            request.addAll(List.of("fq", productionFilter()));
            request.addAll(productionParams(authorities));
            Set<String> found = probeIds(select("audit", request.toArray(new String[0])));

            assertEquals(found, Set.of(PROBE_IN),
                    "the audit-core join must return the probe of a readable node and only that one");
        }
        finally
        {
            updateAudit("{\"delete\":[\"" + PROBE_IN + "\",\"" + PROBE_OUT + "\"]}");
        }
    }
}
