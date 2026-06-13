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

package org.alfresco.solr;

/**
 * Constants for Solr document types used in the Alfresco index.
 * These were previously defined in SolrInformationServer, which has been
 * removed as part of the tracker extraction refactoring.
 */
public final class SolrDocTypeConstants
{
    public static final String DOC_TYPE_NODE = "Node";
    public static final String DOC_TYPE_UNINDEXED_NODE = "UnindexedNode";
    public static final String DOC_TYPE_ERROR_NODE = "ErrorNode";
    public static final String DOC_TYPE_ACL = "Acl";
    public static final String DOC_TYPE_TX = "Tx";
    public static final String DOC_TYPE_ACL_TX = "AclTx";
    public static final String DOC_TYPE_STATE = "State";

    public static final String PREFIX_ERROR = "ERROR-";

    private SolrDocTypeConstants()
    {
        // Utility class
    }
}
