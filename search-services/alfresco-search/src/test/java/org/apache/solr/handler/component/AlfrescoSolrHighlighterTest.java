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

package org.apache.solr.handler.component;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AlfrescoSolrHighlighterTest
{
    /**
     * Alfresco schema field names (e.g. {@code content@s_stored_t____@{ns}content})
     * contain {@code @} and <code>{}</code> characters that cannot be parsed by
     * {@code SolrReturnFields}. DefaultSolrHighlighter (Solr 8) passes the result of
     * {@code getDocPrefetchFieldNames} to {@code new SolrReturnFields(names, req)}
     * for document pre-fetching; a non-null result containing Alfresco field names
     * blows up with "undefined field" (the parser stops at the first {@code @}).
     *
     * <p>Returning {@code null} makes DefaultSolrHighlighter build an empty
     * SolrReturnFields, which wants all stored fields — correct, just without the
     * pre-fetch field restriction.</p>
     */
    @Test
    public void getDocPrefetchFieldNamesReturnsNullToAvoidFieldNameParsing()
    {
        AlfrescoSolrHighlighter highlighter = new AlfrescoSolrHighlighter(null);

        assertNull("Prefetch field names must be null: Alfresco field names "
                        + "(content@s_stored_t____@{ns}content) cannot be parsed by SolrReturnFields",
                highlighter.getDocPrefetchFieldNames(
                        new String[]{"content@s_stored_t____@{http://www.alfresco.org/model/content/1.0}content"},
                        null));
    }
}
