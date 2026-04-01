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

package org.alfresco.solr.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.alfresco.repo.search.adaptor.QueryConstants;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * @author Andy
 *
 */
public class SolrOwnerQuery extends AbstractAuthorityQuery
{
    public SolrOwnerQuery(String authority)
    {
        super(authority);
    }
    
    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException
    {
        if(!(searcher instanceof SolrIndexSearcher))
        {
            throw new IllegalStateException("Must have a SolrIndexSearcher");
        }

        BitSetQuery ownerFilter = getOwnerFilter(authority, (SolrIndexSearcher)searcher);
        return ownerFilter.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
    }

    @Override
    public String toString(String field)
    {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(QueryConstants.FIELD_OWNER).append(':');
        stringBuilder.append(authority);
        return stringBuilder.toString();
    }

    private BitSetQuery getOwnerFilter(String owner, SolrIndexSearcher searcher) throws IOException
    {
        Query query =  new TermQuery(new Term(QueryConstants.FIELD_OWNER, owner));
        BitSetCollector collector = new BitSetCollector(searcher.getTopReaderContext().leaves().size());
        searcher.search(query, collector);
        return collector.getBitSetQuery();
    }

    class BitSetCollector implements Collector, LeafCollector
    {
        private List<FixedBitSet> sets;
        private FixedBitSet set;

        public BitSetCollector(int leafCount)
        {
            this.sets = new ArrayList<FixedBitSet>(leafCount);
        }

        public BitSetQuery getBitSetQuery() {
            return new BitSetQuery(sets);
        }

        public void setScorer(org.apache.lucene.search.Scorable scorer) {

        }

        public void collect(int doc) {
            set.set(doc);
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context)
                throws IOException {
            set = new FixedBitSet(context.reader().maxDoc());
            sets.add(set);
            return this;
        }

        @Override
        public org.apache.lucene.search.ScoreMode scoreMode() {
            return org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES;
        }
    }
}
