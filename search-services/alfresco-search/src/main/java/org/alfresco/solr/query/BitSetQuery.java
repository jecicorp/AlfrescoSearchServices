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

package org.alfresco.solr.query;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * A Lucene Query backed by per-segment FixedBitSets.
 * <p>
 * Replaces the deprecated {@code BitsFilter} which extended
 * {@code org.apache.solr.search.Filter} (removed in Lucene 8).
 * Used for ACL security filtering — each segment's bitset marks
 * the documents that the current user is allowed to see.
 */
public class BitSetQuery extends Query
{
    private final List<FixedBitSet> bitSets;

    public BitSetQuery(List<FixedBitSet> bitSets)
    {
        if (bitSets == null) throw new IllegalStateException("bitSets cannot be null");
        this.bitSets = bitSets;
    }

    public void or(BitSetQuery other)
    {
        List<FixedBitSet> otherSets = other.bitSets;
        for (int i = 0; i < bitSets.size(); i++)
        {
            bitSets.get(i).or(otherSets.get(i));
        }
    }

    public void and(BitSetQuery other)
    {
        List<FixedBitSet> otherSets = other.bitSets;
        for (int i = 0; i < bitSets.size(); i++)
        {
            bitSets.get(i).and(otherSets.get(i));
        }
    }

    public List<FixedBitSet> getBitSets()
    {
        return this.bitSets;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException
    {
        return new ConstantScoreWeight(this, boost)
        {
            @Override
            public boolean isCacheable(LeafReaderContext ctx)
            {
                return false;
            }

            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException
            {
                FixedBitSet bits = bitSets.get(context.ord);
                int cardinality = bits.cardinality();
                if (cardinality == 0)
                {
                    return null;
                }
                DocIdSetIterator iterator = new BitSetIterator(bits, cardinality);
                return new ConstantScoreScorer(this, score(), scoreMode, iterator);
            }
        };
    }

    @Override
    public void visit(QueryVisitor visitor)
    {
        // Matches a precomputed bit set; exposes no scoring terms.
        visitor.visitLeaf(this);
    }

    @Override
    public String toString(String field)
    {
        return "BitSetQuery";
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof BitSetQuery)) return false;
        BitSetQuery that = (BitSetQuery) o;
        return bitSets.equals(that.bitSets);
    }

    @Override
    public int hashCode()
    {
        return bitSets.hashCode();
    }
}
