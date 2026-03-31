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

import static org.junit.Assert.*;

import java.util.List;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.IndexTokenisationMode;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.QName;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.*;

public class PropertyFieldMapperTest
{
    private PropertyFieldMapper mapper;

    @Before
    public void setUp()
    {
        mapper = new PropertyFieldMapper();
    }

    // =========================================================================
    // Stored field names for text properties
    // =========================================================================

    @Test
    public void textBOTH_singleValued_producesStoredFieldWithTokenisedAndUntokenisedFlags()
    {
        // cm:title — TEXT, BOTH, single-valued, NOT cross-locale
        PropertyDefinition propDef = mockTextProperty("cm:title",
                IndexTokenisationMode.BOTH, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals("Should produce exactly 1 stored field", 1, fields.size());
        // t=tokenised, s=untokenised, _=no crossLocale, s=sort, _=no suggest
        assertEquals("text@s_stored_ts_s_@{http://www.alfresco.org/model/content/1.0}cm:title",
                fields.get(0));
    }

    @Test
    public void textBOTH_crossLocale_producesCrossLocaleFlag()
    {
        // cm:name — TEXT, BOTH, single-valued, cross-locale=true
        PropertyDefinition propDef = mockProperty(ContentModel.PROP_NAME,
                DataTypeDefinition.TEXT, IndexTokenisationMode.BOTH, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // t=tokenised, s=untokenised, c=crossLocale, s=sort, _=no suggest
        assertEquals("text@s_stored_tscs_@{http://www.alfresco.org/model/content/1.0}name",
                fields.get(0));
    }

    @Test
    public void textTRUE_producesTokenisedOnlyFlags()
    {
        // TEXT, TRUE, single-valued
        PropertyDefinition propDef = mockTextProperty("cm:description",
                IndexTokenisationMode.TRUE, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // t=tokenised, _=no untokenised, _=no crossLocale, _=no sort, _=no suggest
        assertEquals("text@s_stored_t____@{http://www.alfresco.org/model/content/1.0}cm:description",
                fields.get(0));
    }

    @Test
    public void textFALSE_singleValued_producesUntokenisedAndSortFlags()
    {
        // TEXT, FALSE, single-valued
        PropertyDefinition propDef = mockTextProperty("cm:sitePreset",
                IndexTokenisationMode.FALSE, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // _=no tokenised, s=untokenised, _=no crossLocale, s=sort, _=no suggest
        assertEquals("text@s_stored__s_s_@{http://www.alfresco.org/model/content/1.0}cm:sitePreset",
                fields.get(0));
    }

    @Test
    public void textFALSE_multiValued_noSortFlag()
    {
        // TEXT, FALSE, multi-valued — sort only for single-valued
        PropertyDefinition propDef = mockTextProperty("cm:tags",
                IndexTokenisationMode.FALSE, true);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // m=multiValued, _=no tokenised, s=untokenised, _=no crossLocale, _=no sort (multiValued), _=no suggest
        assertEquals("text@m_stored__s___@{http://www.alfresco.org/model/content/1.0}cm:tags",
                fields.get(0));
    }

    @Test
    public void identifierProperty_treatedAsBOTH()
    {
        // cm:creator — identifier property, forced to BOTH regardless of model definition
        PropertyDefinition propDef = mockProperty(ContentModel.PROP_CREATOR,
                DataTypeDefinition.TEXT, IndexTokenisationMode.TRUE, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // Forced to BOTH: t=tokenised, s=untokenised, _=no crossLocale, s=sort, _=no suggest
        assertTrue("Identifier should have both t and s flags: " + fields.get(0),
                fields.get(0).contains("_stored_ts_s_@"));
    }

    @Test
    public void mltext_producesMultiValuedStoredField()
    {
        // MLTEXT, BOTH
        PropertyDefinition propDef = mockProperty(
                QName.createQName("{http://www.alfresco.org/model/content/1.0}title"),
                DataTypeDefinition.MLTEXT, IndexTokenisationMode.BOTH, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        // mltext is always 'm', and no sort flag (mltext)
        assertTrue("Should start with mltext@m_stored_", fields.get(0).startsWith("mltext@m_stored_"));
        // t=tokenised, s=untokenised, _=no crossLocale, _=no sort (mltext), _=no suggest
        assertTrue("Should have ts flags: " + fields.get(0), fields.get(0).contains("_stored_ts___@"));
    }

    @Test
    public void nonTextProperty_returnsDirectIndexingField()
    {
        // int property — NOT a text type, returns direct field
        PropertyDefinition propDef = mock(PropertyDefinition.class);
        QName propName = QName.createQName("{http://www.alfresco.org/model/content/1.0}size");
        when(propDef.getName()).thenReturn(propName);
        when(propDef.isMultiValued()).thenReturn(false);
        when(propDef.getIndexTokenisationMode()).thenReturn(IndexTokenisationMode.FALSE);
        when(propDef.getFacetable()).thenReturn(org.alfresco.repo.dictionary.Facetable.TRUE);

        DataTypeDefinition dataType = mock(DataTypeDefinition.class);
        when(dataType.getName()).thenReturn(DataTypeDefinition.INT);
        when(propDef.getDataType()).thenReturn(dataType);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        assertEquals(1, fields.size());
        assertEquals("int@sd@{http://www.alfresco.org/model/content/1.0}size", fields.get(0));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PropertyDefinition mockTextProperty(String localName,
                                                 IndexTokenisationMode mode,
                                                 boolean multiValued)
    {
        QName propName = QName.createQName("{http://www.alfresco.org/model/content/1.0}" + localName);
        return mockProperty(propName, DataTypeDefinition.TEXT, mode, multiValued);
    }

    private PropertyDefinition mockProperty(QName propName, QName dataTypeName,
                                             IndexTokenisationMode mode,
                                             boolean multiValued)
    {
        PropertyDefinition propDef = mock(PropertyDefinition.class);
        when(propDef.getName()).thenReturn(propName);
        when(propDef.isMultiValued()).thenReturn(multiValued);
        when(propDef.isIndexed()).thenReturn(true);
        when(propDef.getIndexTokenisationMode()).thenReturn(mode);

        DataTypeDefinition dataType = mock(DataTypeDefinition.class);
        when(dataType.getName()).thenReturn(dataTypeName);
        when(propDef.getDataType()).thenReturn(dataType);

        return propDef;
    }
}
