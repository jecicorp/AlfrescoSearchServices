/*-
 * #%L
 * Alfresco Solr Search
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

package org.alfresco.solr.query.afts.requestHandler;

import static com.google.common.collect.ImmutableMap.of;
import static java.util.Arrays.asList;
import static java.util.stream.IntStream.range;
import static org.alfresco.model.ContentModel.PROP_NAME;
import static org.alfresco.model.ContentModel.PROP_TITLE;
import static org.alfresco.model.ContentModel.TYPE_CONTENT;
import static org.alfresco.solr.AlfrescoSolrUtils.addNode;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.MLTextPropertyValue;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.StringPropertyValue;
import org.alfresco.solr.dataload.TestDataProvider;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A conjunction of unqualified terms must match a document holding every term, whichever
 * default field holds each of them. The fixture spreads the terms on purpose: "cerfa" only
 * lives in cm:name and "apprentissage" only in cm:title of the same record.
 */
@SolrTestCaseJ4.SuppressSSL
public class AFTSDefaultFieldConjunctionIT extends AbstractRequestHandlerIT
{
    private static final String TEMPLATE_NAME = "pristytpl";

    @BeforeClass
    public static void beforeClass() throws Exception
    {
        TestDataProvider dataProvider = new TestDataProvider(h);

        List<Map<String, String>> data = asList(
                of("name", "cerfa cybersecurite",
                        "title", "contrat apprentissage"),
                of("name", "contrat maintenance",
                        "title", "convention"));

        TEST_ROOT_NODEREF = dataProvider.getRootNode();

        range(0, data.size())
                .forEach(index -> {
                    Map<String, String> record = data.get(index);
                    String name = record.get("name");
                    String title = record.get("title");

                    Map<QName, PropertyValue> properties = new HashMap<>();
                    properties.put(PROP_NAME, new StringPropertyValue(name));
                    properties.put(PROP_TITLE, new MLTextPropertyValue(Map.of(Locale.getDefault(), title)));

                    addNode(getCore(),
                            dataModel, 1, index + 2, 1,
                            TYPE_CONTENT, null, properties, null,
                            "the_owner_of_this_node_is" + name,
                            null,
                            new NodeRef[]{ TEST_ROOT_NODEREF },
                            new String[]{ "/" + dataProvider.qName("a_qname_for_node_" + index) },
                            dataProvider.newNodeRef(), true);
                });
    }

    @Test
    public void singleTerm_shouldMatchWhicheverDefaultFieldHoldsIt()
    {
        assertResponseCardinality("cerfa", 1);
        assertResponseCardinality("apprentissage", 1);
        assertResponseCardinality("contrat", 2);
    }

    @Test
    public void conjunction_qualifiedFields_shouldMatchAcrossFields()
    {
        assertResponseCardinality("cm:name:cerfa AND cm:title:apprentissage", 1);
        assertResponseCardinality("TEXT:cerfa AND TEXT:apprentissage", 1);
    }

    @Test
    public void conjunction_unqualifiedTermsInTheSameField_shouldMatch()
    {
        assertResponseCardinality("contrat AND maintenance", 1);
        assertResponseCardinality("cerfa AND cybersecurite", 1);
    }

    @Test
    public void conjunction_unqualifiedTermsInDifferentFields_shouldMatchTheSameDocument()
    {
        assertResponseCardinality("cerfa AND apprentissage", 1);
        assertResponseCardinality("cerfa* AND apprentissage*", 1);
    }

    /**
     * Guard: proves the query template is actually honoured, so that the conjunction
     * assertions below cannot silently pass through the untemplated code path.
     */
    @Test
    public void templatedQuery_shouldOnlySearchTheTemplateFields()
    {
        assertTemplatedResponseCardinality("%(cm:name)", "cerfa", 1);
        assertTemplatedResponseCardinality("%(cm:name)", "apprentissage", 0);
        assertTemplatedResponseCardinality("%(cm:name cm:title)", "apprentissage", 1);
    }

    /**
     * The repository always searches through a query template, so this is the path an end
     * user request actually takes.
     */
    @Test
    public void templatedConjunction_termsInDifferentFields_shouldMatchTheSameDocument()
    {
        assertTemplatedResponseCardinality("%(cm:name cm:title)", "contrat AND maintenance", 1);
        assertTemplatedResponseCardinality("%(cm:name cm:title)", "cerfa AND apprentissage", 1);
    }

    private void assertTemplatedResponseCardinality(String template, String query, int expectedCardinality)
    {
        String json = "{"
                + "\"query\": \"" + query + "\","
                + "\"locales\": [],"
                + "\"templates\": [ { \"name\": \"" + TEMPLATE_NAME + "\", \"template\": \"" + template + "\" } ],"
                + "\"allAttributes\": [],"
                + "\"defaultFTSOperator\": \"OR\","
                + "\"defaultFTSFieldOperator\": \"OR\","
                + "\"defaultNamespace\": \"http://www.alfresco.org/model/content/1.0\","
                + "\"textAttributes\": [],"
                + "\"queryConsistency\": \"DEFAULT\""
                + "}";

        assertQ(areq(params("rows", "20", "qt", "/afts", "q", query, "df", TEMPLATE_NAME), json),
                "*[count(//doc)=" + expectedCardinality + "]");
    }
}
