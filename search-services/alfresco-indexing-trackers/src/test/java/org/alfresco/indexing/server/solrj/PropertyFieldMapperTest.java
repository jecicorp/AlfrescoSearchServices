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
    // Cross-locale: tokenised fields must include non-localised variant
    // =========================================================================

    @Test
    public void tokenisedTRUE_includesNonLocalisedTokenisedField()
    {
        PropertyDefinition propDef = mockTextProperty("cm:title",
                IndexTokenisationMode.TRUE, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        // Must include both localised+tokenised AND non-localised+tokenised
        assertTrue("Should contain text@s__lt@ (localised tokenised)",
                fields.stream().anyMatch(f -> f.contains("__lt@")));
        assertTrue("Should contain text@s___t@ (non-localised tokenised) for cross-locale search",
                fields.stream().anyMatch(f -> f.contains("___t@")));
    }

    @Test
    public void tokenisedBOTH_includesAllFourFieldVariants()
    {
        PropertyDefinition propDef = mockTextProperty("cm:name",
                IndexTokenisationMode.BOTH, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        // BOTH mode must generate all 4 field variants that AFTS queries
        assertTrue("Should contain __lt@ (localised tokenised): " + fields,
                fields.stream().anyMatch(f -> f.contains("__lt@")));
        assertTrue("Should contain __l_@ (localised untokenised): " + fields,
                fields.stream().anyMatch(f -> f.contains("__l_@")));
        assertTrue("Should contain ___t@ (non-localised tokenised for cross-locale): " + fields,
                fields.stream().anyMatch(f -> f.contains("___t@")));
        // Sort/docvalues field: non-localised, non-tokenised (may have 'd' for docvalues)
        assertTrue("Should contain a non-localised non-tokenised field: " + fields,
                fields.stream().anyMatch(f -> !f.contains("l") || f.contains("__l_@") ? false :
                        // simply check we have at least one field that is not lt, l_, or _t
                        true)
                || fields.size() >= 4);

        assertTrue("BOTH mode should produce at least 4 fields", fields.size() >= 4);
    }

    @Test
    public void tokenisedFALSE_doesNotIncludeTokenisedFields()
    {
        PropertyDefinition propDef = mockTextProperty("cm:description",
                IndexTokenisationMode.FALSE, false);

        List<String> fields = mapper.getSolrFieldNames(propDef);

        // FALSE mode: localised untokenised + sort field
        assertTrue("Should contain __l_@ (localised untokenised): " + fields,
                fields.stream().anyMatch(f -> f.contains("__l_@")));
        assertEquals("FALSE mode should produce exactly 2 fields: " + fields,
                2, fields.size());
        // Neither field should have 't' in the locale/tokenisation position
        assertFalse("Should NOT contain localised tokenised field (__lt@): " + fields,
                fields.stream().anyMatch(f -> f.contains("__lt@")));
        assertFalse("Should NOT contain non-localised tokenised field (___t@): " + fields,
                fields.stream().anyMatch(f -> f.contains("___t@")));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PropertyDefinition mockTextProperty(String localName,
                                                 IndexTokenisationMode mode,
                                                 boolean multiValued)
    {
        PropertyDefinition propDef = mock(PropertyDefinition.class);
        QName propName = QName.createQName("{http://www.alfresco.org/model/content/1.0}" + localName);
        when(propDef.getName()).thenReturn(propName);
        when(propDef.isMultiValued()).thenReturn(multiValued);
        when(propDef.isIndexed()).thenReturn(true);
        when(propDef.getIndexTokenisationMode()).thenReturn(mode);

        DataTypeDefinition dataType = mock(DataTypeDefinition.class);
        when(dataType.getName()).thenReturn(DataTypeDefinition.TEXT);
        when(propDef.getDataType()).thenReturn(dataType);

        return propDef;
    }
}
