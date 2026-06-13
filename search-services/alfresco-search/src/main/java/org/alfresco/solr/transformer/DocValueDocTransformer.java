/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
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

package org.alfresco.solr.transformer;

import java.io.IOException;
import java.util.ArrayList;

import org.alfresco.solr.AlfrescoSolrDataModel;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.NumericUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.schema.SchemaField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andy
 */
public class DocValueDocTransformer extends DocTransformer
{
    protected final static Logger log = LoggerFactory.getLogger(DocValueDocTransformer.class);

    @Override
    public String getName()
    {
        return "Alfresco doc value document transformer";
    }

    public void setContext( ResultContext context )
    {
        this.context = context;
    }

    @Override
    public void transform(SolrDocument doc, int docid) throws IOException
    {
        for(String fieldName : context.getSearcher().getFieldNames())
        {
            SchemaField schemaField = context.getSearcher().getSchema().getFieldOrNull(fieldName);
            if(schemaField != null)
            {
                if(schemaField.hasDocValues())
                {
                    SortedDocValues sortedDocValues = context.getSearcher().getSlowAtomicReader().getSortedDocValues(fieldName);
                    if(sortedDocValues != null)
                    {
                        if (sortedDocValues.advanceExact(docid))
                        {
                            int ordinal = sortedDocValues.ordValue();
                            if(ordinal > -1)
                            {
                                doc.removeFields(fieldName);
                                String alfrescoFieldName = AlfrescoSolrDataModel.getInstance().getAlfrescoPropertyFromSchemaField(fieldName);
                                doc.removeFields(alfrescoFieldName);
                                doc.addField(alfrescoFieldName, schemaField.getType().toObject(schemaField, sortedDocValues.lookupOrd(ordinal)));
                            }
                        }
                    }

                    SortedSetDocValues sortedSetDocValues = context.getSearcher().getSlowAtomicReader().getSortedSetDocValues(fieldName);
                    if(sortedSetDocValues != null)
                    {
                        ArrayList<Object> newValues = new ArrayList<Object>();
                        if (sortedSetDocValues.advanceExact(docid))
                        {
                            long ordinal;
                            while ( (ordinal = sortedSetDocValues.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS)
                            {
                                newValues.add(schemaField.getType().toObject(schemaField, sortedSetDocValues.lookupOrd(ordinal)));
                            }
                        }
                        doc.removeFields(fieldName);
                        String alfrescoFieldName = AlfrescoSolrDataModel.getInstance().getAlfrescoPropertyFromSchemaField(fieldName);
                        doc.removeFields(alfrescoFieldName);
                        doc.addField(alfrescoFieldName, newValues);
                    }

                    BinaryDocValues binaryDocValues = context.getSearcher().getSlowAtomicReader().getBinaryDocValues(fieldName);
                    if(binaryDocValues != null)
                    {
                        if (binaryDocValues.advanceExact(docid))
                        {
                            doc.removeFields(fieldName);
                            String alfrescoFieldName = AlfrescoSolrDataModel.getInstance().getAlfrescoPropertyFromSchemaField(fieldName);
                            doc.removeFields(alfrescoFieldName);
                            doc.addField(alfrescoFieldName, schemaField.getType().toObject(schemaField, binaryDocValues.binaryValue()));
                        }
                    }

                    NumericDocValues numericDocValues = context.getSearcher().getSlowAtomicReader().getNumericDocValues(fieldName);
                    if(numericDocValues != null)
                    {
                        if (numericDocValues.advanceExact(docid))
                        {
                            doc.removeFields(fieldName);
                            String alfrescoFieldName = AlfrescoSolrDataModel.getInstance().getAlfrescoPropertyFromSchemaField(fieldName);
                            doc.removeFields(alfrescoFieldName);
                            long val = numericDocValues.longValue();
                            // In Lucene 8, getNumericType() was removed from FieldType.
                            // All numeric doc values are stored as longs. Add directly.
                            doc.addField(alfrescoFieldName, val);
                        }
                    }

                    SortedNumericDocValues sortedNumericDocValues = context.getSearcher().getSlowAtomicReader().getSortedNumericDocValues(fieldName);
                    if(sortedNumericDocValues != null)
                    {
                        if (sortedNumericDocValues.advanceExact(docid))
                        {
                            doc.removeFields(fieldName);
                            String alfrescoFieldName = AlfrescoSolrDataModel.getInstance().getAlfrescoPropertyFromSchemaField(fieldName);
                            doc.removeFields(alfrescoFieldName);
                            ArrayList<Object> newValues = new ArrayList<Object>(sortedNumericDocValues.docValueCount());
                            for(int i = 0; i < sortedNumericDocValues.docValueCount(); i++)
                            {
                                newValues.add(sortedNumericDocValues.nextValue());
                            }
                            doc.addField(alfrescoFieldName, newValues);
                        }
                    }
                }
            }
        }
    }
}
