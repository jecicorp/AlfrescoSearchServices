/*
 * Copyright 2026 - Jeci SARL - https://jeci.fr
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * http://www.gnu.org/licenses/.
 */

package org.alfresco.repo.search.impl.lucene.analysis;

import static org.junit.Assert.assertTrue;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.Test;

/**
 * Validates that a multiValued field with multiple locale values
 * (MLText fr + en) can be indexed in Lucene 8 without offset errors.
 *
 * The key insight: Lucene 8 requires offsets to be monotonically non-decreasing
 * across ALL values of a multiValued field. Between values, Lucene adds
 * {@code analyzer.getOffsetGap()} to the accumulated offset. If the gap is
 * too small relative to the max offset of the previous value, the next
 * value's offsets will appear to go backwards.
 */
public class MultiValuedOffsetTest
{
    /**
     * With offsetGap=1 (Lucene default), indexing two short values works
     * because WhitespaceTokenizer offsets start at 0.
     */
    @Test
    public void testDefaultGap_simpleValues_succeeds() throws Exception
    {
        Analyzer analyzer = simpleAnalyzer(1);
        indexMultipleValues(analyzer, "test", "hello world", "bonjour monde");
        analyzer.close();
    }

    /**
     * With offsetGap=1000 (our AlfrescoAnalyzerWrapper setting),
     * even values with larger offsets work fine.
     */
    @Test
    public void testLargeGap_succeeds() throws Exception
    {
        Analyzer analyzer = simpleAnalyzer(1000);
        indexMultipleValues(analyzer, "test",
                "A very long first value with many tokens",
                "Short second");
        analyzer.close();
    }

    /**
     * Proves that with the offsetGap=1000 fix, three locale values
     * (simulating MLText with fr + en + de) can be indexed.
     */
    @Test
    public void testThreeLocaleValues() throws Exception
    {
        Analyzer analyzer = simpleAnalyzer(1000);
        indexMultipleValues(analyzer, "test",
                "\u0000fr\u0000Le titre",
                "\u0000en\u0000The title",
                "\u0000de\u0000Der Titel");
        analyzer.close();
    }

    private Analyzer simpleAnalyzer(int offsetGap)
    {
        return new Analyzer()
        {
            @Override
            protected TokenStreamComponents createComponents(String fieldName)
            {
                return new TokenStreamComponents(new WhitespaceTokenizer());
            }

            @Override
            public int getOffsetGap(String fieldName)
            {
                return offsetGap;
            }
        };
    }

    private void indexMultipleValues(Analyzer analyzer, String fieldName, String... values) throws Exception
    {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter writer = new IndexWriter(dir, config);

        FieldType ft = new FieldType();
        ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        ft.setTokenized(true);
        ft.setStored(true);
        ft.freeze();

        Document doc = new Document();
        for (String value : values)
        {
            doc.add(new Field(fieldName, value, ft));
        }

        writer.addDocument(doc); // This throws if offsets go backwards
        writer.commit();
        writer.close();

        DirectoryReader reader = DirectoryReader.open(dir);
        assertTrue("Document should be indexed", reader.numDocs() > 0);
        reader.close();
        dir.close();
    }
}
