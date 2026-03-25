/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
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
package org.alfresco.indexing.server.solrj;

import org.alfresco.repo.dictionary.Facetable;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.repo.dictionary.IndexTokenisationMode;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.QName;

/**
 * Maps Alfresco property definitions to Solr dynamic field names.
 *
 * <p>Replicates the field naming logic from {@code AlfrescoSolrDataModel}:
 * <ul>
 *   <li>{@code getFieldForText()} — for text/mltext/content types</li>
 *   <li>{@code getFieldForNonText()} — for int/long/date/boolean/etc.</li>
 * </ul>
 *
 * <p>The generated field names must match exactly what the AFTS query parser
 * expects when resolving property names like {@code cm:userName}.</p>
 */
public class PropertyFieldMapper
{
    /**
     * Returns the Solr field name for the given property definition.
     * This is the primary field used for search (localised + tokenised for text,
     * docvalues for non-text).
     *
     * @param propDef the property definition
     * @return the Solr dynamic field name
     */
    public String getSolrFieldName(PropertyDefinition propDef)
    {
        if (isTextField(propDef))
        {
            // Default: localised=true, tokenised=true — this is what AFTS uses for search
            return getFieldForText(true, true, propDef);
        }
        return getFieldForNonText(propDef);
    }

    /**
     * Builds the Solr field name for non-text properties.
     * Format: {@code {datatype}@{s|m}{d|_}@{qname}}
     *
     * <p>Mirrors {@code AlfrescoSolrDataModel.getFieldForNonText()}.</p>
     */
    String getFieldForNonText(PropertyDefinition propDef)
    {
        StringBuilder builder = new StringBuilder();
        QName dataTypeName = propDef.getDataType().getName();
        builder.append(dataTypeName.getLocalName());
        builder.append("@");
        builder.append(propDef.isMultiValued() ? "m" : "s");
        builder.append(hasDocValues(propDef) ? "d" : "_");
        builder.append("@");
        builder.append(propDef.getName().toString());
        return builder.toString();
    }

    /**
     * Builds the Solr field name for text properties.
     * Format: {@code {datatype}@{s|m}{_}{_}{l|_}{t|_}@{qname}}
     *
     * <p>Mirrors {@code AlfrescoSolrDataModel.getFieldForText(localised, tokenised, sort=false)}.</p>
     */
    String getFieldForText(boolean localised, boolean tokenised, PropertyDefinition propDef)
    {
        StringBuilder builder = new StringBuilder();
        QName dataTypeName = propDef.getDataType().getName();
        builder.append(dataTypeName.getLocalName());
        builder.append("@");

        // Multi-valued flag: MLTEXT is always 'm', CONTENT is always 's'
        if (dataTypeName.equals(DataTypeDefinition.MLTEXT))
        {
            builder.append('m');
        }
        else if (dataTypeName.equals(DataTypeDefinition.CONTENT))
        {
            builder.append('s');
        }
        else
        {
            builder.append(propDef.isMultiValued() ? "m" : "s");
        }

        // DocValues flag: for text with localised/tokenised, always '_'
        if (localised || tokenised
                || dataTypeName.equals(DataTypeDefinition.CONTENT)
                || dataTypeName.equals(DataTypeDefinition.MLTEXT))
        {
            builder.append('_');
        }
        else
        {
            builder.append(hasDocValues(propDef) ? "d" : "_");
        }
        builder.append('_');

        // Locale and tokenization flags
        builder.append(localised ? "l" : "_");
        builder.append(tokenised ? "t" : "_");

        builder.append("@");
        builder.append(propDef.getName().toString());
        return builder.toString();
    }

    /**
     * Checks if a property is a text type (text, mltext, or content).
     */
    static boolean isTextField(PropertyDefinition propDef)
    {
        if (propDef == null || propDef.getDataType() == null)
        {
            return false;
        }
        QName name = propDef.getDataType().getName();
        return name.equals(DataTypeDefinition.MLTEXT)
                || name.equals(DataTypeDefinition.CONTENT)
                || name.equals(DataTypeDefinition.TEXT);
    }

    /**
     * Determines if the property should have Solr DocValues.
     * Mirrors {@code AlfrescoSolrDataModel.hasDocValues()}.
     */
    private boolean hasDocValues(PropertyDefinition propDef)
    {
        if (isTextField(propDef))
        {
            return propDef.getFacetable() != Facetable.FALSE;
        }
        else
        {
            if (propDef.getFacetable() == Facetable.FALSE)
            {
                return false;
            }
            else if (propDef.getFacetable() == Facetable.TRUE)
            {
                return true;
            }
            else
            {
                if (propDef.getIndexTokenisationMode() == IndexTokenisationMode.TRUE)
                {
                    return false;
                }
                return isPrimitive(propDef.getDataType());
            }
        }
    }

    /**
     * Checks if a data type is a primitive type that supports DocValues.
     * Mirrors {@code AlfrescoSolrDataModel.isPrimitive()}.
     */
    private static boolean isPrimitive(DataTypeDefinition dataType)
    {
        QName name = dataType.getName();
        return name.equals(DataTypeDefinition.INT)
                || name.equals(DataTypeDefinition.LONG)
                || name.equals(DataTypeDefinition.FLOAT)
                || name.equals(DataTypeDefinition.DOUBLE)
                || name.equals(DataTypeDefinition.DATE)
                || name.equals(DataTypeDefinition.DATETIME)
                || name.equals(DataTypeDefinition.BOOLEAN)
                || name.equals(DataTypeDefinition.CATEGORY)
                || name.equals(DataTypeDefinition.NODE_REF);
    }
}
