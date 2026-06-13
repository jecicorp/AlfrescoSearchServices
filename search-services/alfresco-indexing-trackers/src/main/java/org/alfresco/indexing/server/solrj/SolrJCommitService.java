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

package org.alfresco.indexing.server.solrj;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;

/**
 * Handles commit and rollback operations via SolrJ.
 */
public class SolrJCommitService
{
    private final SolrClient solrClient;
    private final String collection;

    public SolrJCommitService(SolrClient solrClient, String collection)
    {
        this.solrClient = solrClient;
        this.collection = collection;
    }

    /**
     * Performs a soft commit: makes recent changes visible without a full flush to disk.
     */
    public void commit() throws IOException
    {
        try
        {
            // softCommit=true, waitSearcher=true (default), waitFlush=true (default)
            solrClient.commit(collection, true, true, true);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Soft commit failed", e);
        }
    }

    /**
     * Performs a hard commit: flushes to disk without opening a new searcher.
     * Equivalent to SolrInformationServer.hardCommit() which sets
     * openSearcher=false, softCommit=false, waitSearcher=false.
     */
    public void hardCommit() throws IOException
    {
        try
        {
            // waitFlush=true, waitSearcher=false, softCommit=false
            solrClient.commit(collection, true, false, false);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Hard commit failed", e);
        }
    }

    /**
     * Performs a commit with control over whether a new searcher is opened.
     *
     * @param openSearcher if true, a new searcher is opened after the commit
     * @return true if the commit was performed
     */
    public boolean commit(boolean openSearcher) throws IOException
    {
        try
        {
            // softCommit=false, waitFlush=true, waitSearcher=openSearcher
            solrClient.commit(collection, true, openSearcher, false);
            return true;
        }
        catch (SolrServerException e)
        {
            throw new IOException("Commit failed", e);
        }
    }

    /**
     * Rolls back all uncommitted changes.
     */
    public void rollback() throws IOException
    {
        try
        {
            solrClient.rollback(collection);
        }
        catch (SolrServerException e)
        {
            throw new IOException("Rollback failed", e);
        }
    }
}
