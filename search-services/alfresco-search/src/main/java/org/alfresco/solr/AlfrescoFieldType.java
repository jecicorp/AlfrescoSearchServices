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

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.QueryBuilder;
import org.apache.solr.analysis.TokenizerChain;
import org.apache.solr.common.SolrException;
import org.apache.solr.response.TextResponseWriter;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.QParser;
import org.apache.solr.uninverting.UninvertingReader.Type;

/**
 * Basic behaviour filtched from TextField
 * 
 * @author Andy
 */
public class AlfrescoFieldType extends FieldType
{
    IndexSchema schema;

    /*
     * (non-Javadoc)
     * @see org.apache.solr.schema.FieldType#init(org.apache.solr.schema.IndexSchema, java.util.Map)
     */
    @Override
    protected void init(IndexSchema schema, Map<String, String> args)
    {
        this.schema = schema;
        properties |= TOKENIZED;
        properties &= ~OMIT_TF_POSITIONS;
        super.init(schema, args);
        
        // TODO: Wire up localised analysis driven from the schema
        // for now we do something basic
        setIndexAnalyzer(new AlfrescoAnalyzerWrapper(schema, AlfrescoAnalyzerWrapper.Mode.INDEX));
        setQueryAnalyzer(new AlfrescoAnalyzerWrapper(schema, AlfrescoAnalyzerWrapper.Mode.QUERY));
    }

    /*
     * (non-Javadoc)
     * @see org.apache.solr.schema.FieldType#getSortField(org.apache.solr.schema.SchemaField, boolean)
     */
    @Override
    public SortField getSortField(SchemaField field, boolean reverse)
    {
        return getStringSort(field, reverse);
    }

    /*
     * (non-Javadoc)
     * @see org.apache.solr.schema.FieldType#write(org.apache.solr.response.TextResponseWriter, java.lang.String,
     * org.apache.lucene.index.IndexableField)
     */
    @Override
    public void write(TextResponseWriter writer, String name, IndexableField f) throws IOException
    {
        writer.writeStr(name, f.stringValue(), true);
    }

    @Override
    public Query getFieldQuery(QParser parser, SchemaField field, String externalVal)
    {
        return parseFieldQuery(parser, getQueryAnalyzer(), field.getName(), externalVal);
    }

    @Override
    public Object toObject(SchemaField sf, BytesRef term)
    {
        return term.utf8ToString();
    }

    @Override
    public Query getRangeQuery(QParser parser, SchemaField field, String part1, String part2, boolean minInclusive, boolean maxInclusive)
    {
        Analyzer multiAnalyzer = constructMultiTermAnalyzer(getQueryAnalyzer());
        BytesRef lower = analyzeMultiTerm(field.getName(), part1, multiAnalyzer);
        BytesRef upper = analyzeMultiTerm(field.getName(), part2, multiAnalyzer);
        return new TermRangeQuery(field.getName(), lower, upper, minInclusive, maxInclusive);
    }

    private Analyzer constructMultiTermAnalyzer(Analyzer queryAnalyzer)
    {
        if (queryAnalyzer == null)
            return null;

        if (!(queryAnalyzer instanceof TokenizerChain))
        {
            return new KeywordAnalyzer();
        }

        // In Lucene 8, TokenizerChain provides getMultiTermAnalyzer() directly,
        // replacing the old MultiTermAwareComponent pattern.
        TokenizerChain tc = (TokenizerChain) queryAnalyzer;
        Analyzer multiTermAnalyzer = tc.getMultiTermAnalyzer();
        return multiTermAnalyzer != null ? multiTermAnalyzer : new KeywordAnalyzer();
    }

    public static BytesRef analyzeMultiTerm(String field, String part, Analyzer analyzerIn)
    {
        if (part == null || analyzerIn == null)
            return null;

        TokenStream source = null;
        try
        {
            source = analyzerIn.tokenStream(field, part);
            source.reset();

            TermToBytesRefAttribute termAtt = source.getAttribute(TermToBytesRefAttribute.class);
            BytesRef bytes = termAtt.getBytesRef();

            if (!source.incrementToken())
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "analyzer returned no terms for multiTerm term: " + part);
            if (source.incrementToken())
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "analyzer returned too many terms for multiTerm term: " + part);

            source.end();
            return BytesRef.deepCopyOf(bytes);
        }
        catch (IOException e)
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "error analyzing range part: " + part, e);
        }
        finally
        {
            IOUtils.closeWhileHandlingException(source);
        }
    }

    static Query parseFieldQuery(QParser parser, Analyzer analyzer, String field, String queryText)
    {
        // note, this method always worked this way (but nothing calls it?) because it has no idea of quotes...
        return new QueryBuilder(analyzer).createPhraseQuery(field, queryText);
    }

    @Override
    public Object marshalSortValue(Object value) 
    {
    	return marshalStringSortValue(value);
    }

    @Override
    public Object unmarshalSortValue(Object value) 
    {
    	return unmarshalStringSortValue(value);
    }
    
    protected boolean supportsAnalyzers() 
    {
        return true;
    }
    
    @Override
    public Type getUninversionType(SchemaField sf) {
      return Type.SORTED_SET_BINARY;
    }
}
