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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.Facetable;
import org.alfresco.repo.dictionary.IndexTokenisationMode;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.QName;

/**
 * Maps Alfresco property definitions to Solr field names.
 *
 * <p>For text properties (text, mltext, content), returns a single <b>stored</b> field
 * name following the pattern {@code {type}@{s|m}_stored_{flags}@{qname}}. Solr's
 * {@code generated_copy_fields.xml} defines copyField directives that automatically
 * populate the indexing fields (tokenised, untokenised, sort, cross-locale, etc.)
 * from the stored field. This matches the upstream {@code SolrInformationServer}
 * approach and enables highlighting via {@code AlfrescoSolrHighlighter}.</p>
 *
 * <p>For non-text properties (int, long, date, etc.), returns the direct indexing
 * field name as before.</p>
 *
 * <p>Mirrors the stored field naming from:
 * <ul>
 *   <li>{@code AlfrescoSolrDataModel.getStoredTextField()}</li>
 *   <li>{@code AlfrescoSolrDataModel.getStoredMLTextField()}</li>
 *   <li>{@code AlfrescoSolrDataModel.getStoredContentField()}</li>
 * </ul></p>
 */
public class PropertyFieldMapper
{
    /**
     * Identifier properties are forced to untokenised search by the AFTS query
     * parser in {@code AlfrescoSolrDataModel}, regardless of their model definition.
     * We must index them into the untokenised fields as well, otherwise AFTS
     * won't find them. See the comment in contentModel.xml:
     * "The tokenisation set here is ignored - it is fixed for this type".
     */
    private static final Set<QName> IDENTIFIER_PROPERTIES = new HashSet<>();
    static
    {
        IDENTIFIER_PROPERTIES.add(ContentModel.PROP_CREATOR);
        IDENTIFIER_PROPERTIES.add(ContentModel.PROP_MODIFIER);
        IDENTIFIER_PROPERTIES.add(ContentModel.PROP_USERNAME);
        IDENTIFIER_PROPERTIES.add(ContentModel.PROP_AUTHORITY_NAME);
    }

    /**
     * Properties configured for cross-locale search. Must match the
     * {@code alfresco.cross.locale.property.*} entries in shared.properties.
     */
    private static final Set<QName> CROSS_LOCALE_PROPERTIES = new HashSet<>();
    static
    {
        CROSS_LOCALE_PROPERTIES.add(ContentModel.PROP_NAME);
        CROSS_LOCALE_PROPERTIES.add(ContentModel.PROP_LOCK_OWNER);
    }

    /**
     * Returns the Solr field name(s) for the given property definition.
     *
     * <p>For text properties, returns a single stored field name. The Solr
     * copyField directives in {@code generated_copy_fields.xml} handle
     * populating all indexing fields (tokenised, untokenised, sort, etc.).</p>
     *
     * <p>For non-text properties, returns the direct indexing field name.</p>
     *
     * @param propDef the property definition
     * @return list of Solr field names to index into (typically one element)
     */
    public List<String> getSolrFieldNames(PropertyDefinition propDef)
    {
        if (!isTextField(propDef))
        {
            return Collections.singletonList(getFieldForNonText(propDef));
        }

        List<String> fields = new ArrayList<>();

        // Primary field: the stored field. The copyField directives in
        // generated_copy_fields.xml handle populating search/sort fields.
        fields.add(getStoredFieldName(propDef));

        // DocValues identifier fields (text@sd___@, text@md___@) are NOT covered
        // by copyField rules — they must be populated directly.
        // Mirrors upstream SolrInformationServer.stringProperty() which adds these
        // alongside the stored field.
        IndexTokenisationMode mode = getEffectiveMode(propDef);
        if (mode == IndexTokenisationMode.FALSE || mode == IndexTokenisationMode.BOTH)
        {
            QName dataTypeName = propDef.getDataType().getName();
            if (dataTypeName.equals(DataTypeDefinition.TEXT))
            {
                String prefix = propDef.isMultiValued() ? "md" : "sd";
                fields.add("text@" + prefix + "___@" + propDef.getName().toString());
            }
        }

        return fields;
    }

    /**
     * Returns the effective tokenisation mode, accounting for identifier overrides.
     */
    private static IndexTokenisationMode getEffectiveMode(PropertyDefinition propDef)
    {
        if (IDENTIFIER_PROPERTIES.contains(propDef.getName()))
        {
            return IndexTokenisationMode.BOTH;
        }
        IndexTokenisationMode mode = propDef.getIndexTokenisationMode();
        return mode != null ? mode : IndexTokenisationMode.TRUE;
    }

    /**
     * Builds the stored field name for a text property.
     * Format: {@code {type}@{s|m}_stored_{t}{s}{c}{sort}{suggest}@{qname}}
     *
     * <p>Mirrors the upstream methods:
     * <ul>
     *   <li>{@code AlfrescoSolrDataModel.getStoredTextField()}</li>
     *   <li>{@code AlfrescoSolrDataModel.getStoredMLTextField()}</li>
     *   <li>{@code AlfrescoSolrDataModel.getStoredContentField()}</li>
     * </ul></p>
     *
     * <p>The flags determine which copyField directives fire in
     * {@code generated_copy_fields.xml}, populating the correct indexing fields.</p>
     */
    String getStoredFieldName(PropertyDefinition propDef)
    {
        QName dataTypeName = propDef.getDataType().getName();
        QName propertyName = propDef.getName();

        IndexTokenisationMode mode = getEffectiveMode(propDef);

        StringBuilder sb = new StringBuilder();

        // Data type prefix
        sb.append(dataTypeName.getLocalName());
        sb.append('@');

        // Multi-valued flag
        if (dataTypeName.equals(DataTypeDefinition.MLTEXT))
        {
            sb.append('m');
        }
        else if (dataTypeName.equals(DataTypeDefinition.CONTENT))
        {
            sb.append('s');
        }
        else
        {
            sb.append(propDef.isMultiValued() ? 'm' : 's');
        }

        sb.append("_stored_");

        // Flag: tokenised (t)
        boolean tokenised = (mode == IndexTokenisationMode.TRUE || mode == IndexTokenisationMode.BOTH);
        sb.append(tokenised ? 't' : '_');

        // Flag: untokenised (s)
        boolean untokenised = (mode == IndexTokenisationMode.FALSE || mode == IndexTokenisationMode.BOTH
                || IDENTIFIER_PROPERTIES.contains(propertyName));
        sb.append(untokenised ? 's' : '_');

        // Flag: cross-locale (c)
        boolean crossLocale = CROSS_LOCALE_PROPERTIES.contains(propertyName);
        sb.append(crossLocale ? 'c' : '_');

        // Flag: sort (s) — only for TEXT single-valued with untokenised mode
        // MLTEXT and CONTENT never have a sort flag
        boolean sort = untokenised && !propDef.isMultiValued()
                && !dataTypeName.equals(DataTypeDefinition.MLTEXT)
                && !dataTypeName.equals(DataTypeDefinition.CONTENT);
        sb.append(sort ? 's' : '_');

        // Flag: suggestable (s) — not configured in our deployment
        sb.append('_');

        sb.append('@');
        sb.append(propertyName.toString());

        return sb.toString();
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
