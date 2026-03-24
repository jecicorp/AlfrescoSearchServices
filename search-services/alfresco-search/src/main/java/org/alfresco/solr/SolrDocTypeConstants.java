/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
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
