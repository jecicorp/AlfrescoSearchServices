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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the permission filter of an audit query: Alfresco's own
 * {@code +(AUTHSET) -(DENYSET)} expressed in plain Solr, so the audit core needs no
 * Alfresco query parser.
 */
public final class AuditPermissionFilter
{
    private static final String SCOPE = "auditScope";
    private static final String TYPE = "auditType";
    private static final String READER = "auditReader";
    private static final String READER_QUERY = "auditReaderQuery";
    private static final String DENIED = "auditDenied";
    private static final String DENIED_QUERY = "auditDeniedQuery";

    /**
     * @param fq the filter query to add to the request
     * @param params request parameters the filter query dereferences
     */
    public record Filter(String fq, Map<String, String> params)
    {
    }

    private AuditPermissionFilter()
    {
    }

    /**
     * @param authorities the caller's authorities; never empty, never holding a blank entry
     * @return the filter query and the parameters it references
     * @throws IllegalArgumentException if the authority set is unusable
     */
    public static Filter build(List<String> authorities)
    {
        if (authorities == null || authorities.isEmpty())
        {
            throw new IllegalArgumentException("an audit query needs a non-empty authority set");
        }
        if (authorities.stream().anyMatch(a -> a == null || a.isBlank()))
        {
            throw new IllegalArgumentException("an authority is null or blank");
        }
        if (authorities.stream().anyMatch(a -> a.indexOf(',') >= 0))
        {
            throw new IllegalArgumentException(
                    "an authority contains a comma, which request parameter binding would split in two");
        }

        String quoted = authorities.stream()
                .map(AuditPermissionFilter::quote)
                .collect(Collectors.joining(" OR "));

        Map<String, String> params = new LinkedHashMap<>();
        params.put(TYPE, "DOC_TYPE:Node");
        params.put(READER_QUERY, "READER:(" + quoted + ")");
        params.put(DENIED_QUERY, "DENIED:(" + quoted + ")");
        params.put(READER, "{!join from=ACLID to=ACLID v=$" + READER_QUERY + "}");
        params.put(DENIED, "{!join from=ACLID to=ACLID v=$" + DENIED_QUERY + "}");

        params.put(SCOPE, "{!bool filter=$" + TYPE + " must=$" + READER + " must_not=$" + DENIED + "}");

        String fq = "{!lucene}"
                + "_query_:\"{!join fromIndex=alfresco from=DBID to=NODE_DBID v=$" + SCOPE + "}\""
                + " OR "
                + "_query_:\"{!join fromIndex=archive from=DBID to=NODE_DBID v=$" + SCOPE + "}\"";

        return new Filter(fq, params);
    }

    static String quote(String value)
    {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
