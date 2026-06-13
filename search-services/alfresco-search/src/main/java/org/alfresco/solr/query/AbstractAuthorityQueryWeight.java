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

package org.alfresco.solr.query;

import java.io.IOException;

import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Weight;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * Abstract {@link Weight} implementation for authority related queries.
 * 
 * @see AbstractAuthoritySetQuery
 */
public abstract class AbstractAuthorityQueryWeight extends Weight
{
    protected Query query;
    protected SolrIndexSearcher searcher;
    protected float value;
    protected boolean needsScores;
    
    public AbstractAuthorityQueryWeight(SolrIndexSearcher searcher, boolean needsScores, Query query, String authTermName, String authTermText) throws IOException
    {
    	super(query);
        this.searcher = searcher;
        // Historically primed collection/term statistics here, but the computed
        // values were discarded. Lucene 9 changed TermStates.build/termStatistics
        // signatures; since the results were unused, the priming is dropped.
        this.needsScores = needsScores;
    }
    
    @Override
    public Explanation explain(LeafReaderContext context, int doc)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx)
    {
        return false;
    }
}
