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

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

import org.alfresco.repo.search.MLAnalysisMode;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.junit.Test;

/**
 * Tests that MLTokenDuplicator and MonotonicOffsetFilter produce
 * monotonically non-decreasing offsets as required by Lucene 8+.
 */
public class MLAnalayserOffsetTest
{
    /**
     * MLTokenDuplicator with multiple locale prefixes creates duplicate
     * tokens. Verify offsets are monotonic even with interleaved duplicates.
     */
    @Test
    public void testMLTokenDuplicator_offsetsAreMonotonic() throws Exception
    {
        // Simulate: WhitespaceTokenizer produces tokens with normal offsets,
        // then MLTokenDuplicator creates locale-prefixed duplicates.
        // With EXACT_LANGUAGE_AND_ALL mode, we get both "{locale}term" and "term".
        String text = "The Quick Brown Fox";

        Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new WhitespaceTokenizer();
                // EXACT_LANGUAGE_AND_ALL produces both prefixed and unprefixed tokens
                MLTokenDuplicator duplicator = new MLTokenDuplicator(Locale.ENGLISH, MLAnalysisMode.EXACT_LANGUAGE_AND_ALL);
                duplicator.source = source;
                MonotonicOffsetFilter filter = new MonotonicOffsetFilter(duplicator);
                return new TokenStreamComponents(source, filter);
            }
        };

        assertMonotonicOffsets(analyzer, "test", text);
        analyzer.close();
    }

    /**
     * Test with EXACT_LANGUAGE mode (single prefix).
     */
    @Test
    public void testMLTokenDuplicator_exactLanguage() throws Exception
    {
        String text = "Le titre du document";

        Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new WhitespaceTokenizer();
                MLTokenDuplicator duplicator = new MLTokenDuplicator(Locale.FRENCH, MLAnalysisMode.EXACT_LANGUAGE);
                duplicator.source = source;
                MonotonicOffsetFilter filter = new MonotonicOffsetFilter(duplicator);
                return new TokenStreamComponents(source, filter);
            }
        };

        assertMonotonicOffsets(analyzer, "test", text);
        analyzer.close();
    }

    /**
     * Test MonotonicOffsetFilter with a TokenStream that deliberately
     * produces backwards offsets (simulating the real bug).
     */
    @Test
    public void testMonotonicOffsetFilter_clampsBackwardsOffsets() throws Exception
    {
        // Create a token stream with deliberate backwards offsets
        TokenStream badOffsets = new TokenStream() {
            private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
            private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
            private int pos = 0;
            // Tokens: (0,2), (3,5), (1,4) — the third goes backwards!
            private final int[][] offsets = {{0, 2}, {3, 5}, {1, 4}};
            private final String[] terms = {"aa", "bb", "cc"};

            @Override
            public boolean incrementToken() {
                if (pos >= offsets.length) return false;
                clearAttributes();
                termAtt.setEmpty().append(terms[pos]);
                offsetAtt.setOffset(offsets[pos][0], offsets[pos][1]);
                pos++;
                return true;
            }

            @Override
            public void reset() throws IOException {
                super.reset();
                pos = 0;
            }
        };

        MonotonicOffsetFilter filter = new MonotonicOffsetFilter(badOffsets);
        OffsetAttribute offsetAtt = filter.getAttribute(OffsetAttribute.class);
        CharTermAttribute termAtt = filter.getAttribute(CharTermAttribute.class);

        filter.reset();

        int lastStartOffset = 0;
        while (filter.incrementToken()) {
            int startOff = offsetAtt.startOffset();
            int endOff = offsetAtt.endOffset();

            assertTrue(
                String.format("Offsets must not go backwards: term='%s' startOffset=%d endOffset=%d lastStartOffset=%d",
                    termAtt.toString(), startOff, endOff, lastStartOffset),
                startOff >= lastStartOffset && endOff >= startOff);

            lastStartOffset = startOff;
        }
        filter.end();
        filter.close();
    }

    private void assertMonotonicOffsets(Analyzer analyzer, String fieldName, String text) throws Exception
    {
        int lastStartOffset = 0;
        try (TokenStream ts = analyzer.tokenStream(fieldName, new StringReader(text)))
        {
            CharTermAttribute termAtt = ts.getAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = ts.getAttribute(OffsetAttribute.class);
            ts.reset();

            while (ts.incrementToken())
            {
                int startOff = offsetAtt.startOffset();
                int endOff = offsetAtt.endOffset();

                assertTrue(
                    String.format("Offsets must not go backwards: token='%s' startOffset=%d endOffset=%d lastStartOffset=%d",
                        termAtt.toString(), startOff, endOff, lastStartOffset),
                    startOff >= lastStartOffset && endOff >= startOff);

                lastStartOffset = startOff;
            }
            ts.end();
        }
    }
}
