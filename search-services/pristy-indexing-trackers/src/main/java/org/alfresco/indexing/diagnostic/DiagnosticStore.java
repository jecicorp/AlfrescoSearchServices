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

package org.alfresco.indexing.diagnostic;

import java.io.IOException;
import java.util.Optional;

import org.alfresco.indexing.server.solrj.SolrDocumentMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keeps the last successful index diagnostic of each core in that core, as its {@code DIAGNOSTIC!LAST} document.
 */
public class DiagnosticStore
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticStore.class);
    private static final char LOCALE_MARKER = '\u0000';

    private final SolrClient solrClient;
    private final ObjectMapper mapper;

    public DiagnosticStore(SolrClient solrClient, ObjectMapper mapper)
    {
        this.solrClient = solrClient;
        this.mapper = mapper;
    }

    /**
     * Overwrites the stored diagnostic of a core; the core's next tracker commit makes it durable.
     */
    public void save(String core, StoredDiagnostic diagnostic) throws IOException
    {
        String json = mapper.writeValueAsString(diagnostic);
        try
        {
            solrClient.add(core, SolrDocumentMapper.toDiagnosticDoc(json));
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to store the index diagnostic of core '" + core + "'", e);
        }
    }

    /**
     * Reads the stored diagnostic of a core back through real-time get, committed or not.
     *
     * @return the stored diagnostic, or nothing when the core holds none or one this version cannot read
     */
    public Optional<StoredDiagnostic> load(String core) throws IOException
    {
        SolrDocument document;
        try
        {
            document = solrClient.getById(core, SolrDocumentMapper.DIAGNOSTIC_DOC_ID);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Failed to read the index diagnostic of core '" + core + "'", e);
        }
        Object json = document == null ? null : document.getFirstValue(SolrDocumentMapper.FIELD_DIAGNOSTIC);
        if (json == null)
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(mapper.readValue(stripLocalePrefix(json.toString()), StoredDiagnostic.class));
        }
        catch (JsonProcessingException e)
        {
            LOGGER.warn("Ignoring the stored index diagnostic of core {}: {}", core, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private static String stripLocalePrefix(String value)
    {
        if (!value.isEmpty() && value.charAt(0) == LOCALE_MARKER)
        {
            int end = value.indexOf(LOCALE_MARKER, 1);
            if (end >= 0)
            {
                return value.substring(end + 1);
            }
        }
        return value;
    }
}
