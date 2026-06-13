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

package org.alfresco.solr.component;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

/**
 * Adds consistency information to the search results.
 *
 * When trackers are running in-process (legacy mode), this component retrieved
 * tracker state to report indexing lag. In the new architecture where trackers
 * run as a separate Spring Boot service, this component reports "unknown"
 * consistency state. Clients should query the tracker service directly for
 * indexing status.
 */
public class ConsistencyComponent extends SearchComponent
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Override
    public void prepare(ResponseBuilder rb) throws IOException
    {
        // No preparation required.
    }

    @Override
    public void finishStage(ResponseBuilder rb)
    {
        super.finishStage(rb);
        if (rb.stage != ResponseBuilder.STAGE_GET_FIELDS)
            return;
        try {
            process(rb);
        } catch (IOException e) {
            LOG.error(e.getMessage());
        }
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException
    {
        boolean isShard = rb.req.getParams().getBool(ShardParams.IS_SHARD, false);
        if (!isShard)
        {
            long[] txState = getLastIndexedTxFromIndex(rb);
            long lastIndexedTx = txState[0];
            rb.rsp.add("lastIndexedTx", lastIndexedTx);
            rb.rsp.add("lastIndexedTxTime", txState[1]);

            long lastTxOnServer = getLastTxOnServerFromIndex(rb);
            long txRemaining = (lastTxOnServer > 0 && lastIndexedTx > 0)
                    ? Math.max(0, lastTxOnServer - lastIndexedTx) : -1;
            rb.rsp.add("txRemaining", txRemaining);
        }
    }

    /**
     * Reads the last indexed transaction ID and commit time from the Solr index.
     * Queries for DOC_TYPE:Tx documents sorted by TXID descending, returns the
     * first hit's TXID and TXCOMMITTIME values.
     *
     * @return long[2] where [0]=lastIndexedTx, [1]=lastIndexedTxTime; both -1 if unknown
     */
    private long[] getLastIndexedTxFromIndex(ResponseBuilder rb)
    {
        long[] result = {-1, -1};
        try
        {
            SolrIndexSearcher searcher = rb.req.getSearcher();
            // DOC_TYPE is a string field — query for exact value "Tx"
            TermQuery docTypeQuery = new TermQuery(new Term("DOC_TYPE", "Tx"));
            // Sort by TXID descending to get the most recent transaction
            Sort sort = new Sort(new SortField("TXID", SortField.Type.LONG, true));
            TopDocs topDocs = searcher.search(docTypeQuery, 1, sort);
            if (topDocs.scoreDocs.length > 0)
            {
                int docId = topDocs.scoreDocs[0].doc;
                // Read TXID and TXCOMMITTIME from docValues (Lucene 6 API)
                for (LeafReaderContext ctx : searcher.getIndexReader().leaves())
                {
                    int localDocId = docId - ctx.docBase;
                    if (localDocId >= 0 && localDocId < ctx.reader().maxDoc())
                    {
                        LeafReader reader = ctx.reader();
                        NumericDocValues txIdDv = reader.getNumericDocValues("TXID");
                        NumericDocValues txTimeDv = reader.getNumericDocValues("TXCOMMITTIME");
                        if (txIdDv != null && txIdDv.advanceExact(localDocId))
                        {
                            result[0] = txIdDv.longValue();
                        }
                        if (txTimeDv != null && txTimeDv.advanceExact(localDocId))
                        {
                            result[1] = txTimeDv.longValue();
                        }
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to read tracker state from index: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Reads the last TX ID known to the repository from the tracker state document
     * (DOC_TYPE=State, id=TRACKER!STATE). The tracker writes this document before
     * each commit so that txRemaining can be computed without contacting the repository.
     *
     * @return lastTxIdOnServer from the State document's S_TXID field, or -1 if not found
     */
    private long getLastTxOnServerFromIndex(ResponseBuilder rb)
    {
        try
        {
            SolrIndexSearcher searcher = rb.req.getSearcher();
            TermQuery stateQuery = new TermQuery(new Term("DOC_TYPE", "State"));
            TopDocs topDocs = searcher.search(stateQuery, 10);
            for (int i = 0; i < topDocs.scoreDocs.length; i++)
            {
                int docId = topDocs.scoreDocs[i].doc;
                // Check this is the tracker state doc (not the cap doc) by reading S_TXID
                for (LeafReaderContext ctx : searcher.getIndexReader().leaves())
                {
                    int localDocId = docId - ctx.docBase;
                    if (localDocId >= 0 && localDocId < ctx.reader().maxDoc())
                    {
                        NumericDocValues sTxIdDv = ctx.reader().getNumericDocValues("S_TXID");
                        if (sTxIdDv != null && sTxIdDv.advanceExact(localDocId))
                        {
                            long value = sTxIdDv.longValue();
                            if (value > 0)
                            {
                                return value;
                            }
                        }
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to read tracker state (S_TXID) from index: {}", e.getMessage());
        }
        return -1;
    }

    @Override
    public String getDescription()
    {
        return "Adds consistency information to the search results.";
    }

}
