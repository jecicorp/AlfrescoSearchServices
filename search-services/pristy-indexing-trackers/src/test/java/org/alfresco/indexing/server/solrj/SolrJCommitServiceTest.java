/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.server.solrj;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SolrJCommitServiceTest
{
    private static final String COLLECTION = "alfresco";

    @Mock
    private SolrClient solrClient;

    private SolrJCommitService commitService;

    @Before
    public void setUp()
    {
        commitService = new SolrJCommitService(solrClient, COLLECTION);
    }

    @Test
    public void commit_shouldPerformSoftCommit() throws Exception
    {
        commitService.commit();

        // soft commit: waitFlush=true, waitSearcher=true, softCommit=true
        verify(solrClient).commit(COLLECTION, true, true, true);
    }

    @Test
    public void hardCommit_shouldCommitWithoutOpeningSearcher() throws Exception
    {
        commitService.hardCommit();

        // hard commit: waitFlush=true, waitSearcher=false, softCommit=false
        verify(solrClient).commit(COLLECTION, true, false, false);
    }

    @Test
    public void commitWithOpenSearcher_true_shouldOpenSearcher() throws Exception
    {
        boolean result = commitService.commit(true);

        // openSearcher=true: waitFlush=true, waitSearcher=true, softCommit=false
        verify(solrClient).commit(COLLECTION, true, true, false);
        assertTrue(result);
    }

    @Test
    public void commitWithOpenSearcher_false_shouldNotOpenSearcher() throws Exception
    {
        boolean result = commitService.commit(false);

        // openSearcher=false: waitFlush=true, waitSearcher=false, softCommit=false
        verify(solrClient).commit(COLLECTION, true, false, false);
        assertTrue(result);
    }

    @Test
    public void rollback_shouldCallSolrClientRollback() throws Exception
    {
        commitService.rollback();

        verify(solrClient).rollback(COLLECTION);
    }

    @Test
    public void commit_shouldWrapSolrServerExceptionInIOException() throws Exception
    {
        when(solrClient.commit(COLLECTION, true, true, true))
                .thenThrow(new SolrServerException("connection refused"));

        assertThrows(IOException.class, () -> commitService.commit());
    }

    @Test
    public void hardCommit_shouldWrapSolrServerExceptionInIOException() throws Exception
    {
        when(solrClient.commit(COLLECTION, true, false, false))
                .thenThrow(new SolrServerException("connection refused"));

        assertThrows(IOException.class, () -> commitService.hardCommit());
    }

    @Test
    public void rollback_shouldWrapSolrServerExceptionInIOException() throws Exception
    {
        when(solrClient.rollback(COLLECTION))
                .thenThrow(new SolrServerException("connection refused"));

        assertThrows(IOException.class, () -> commitService.rollback());
    }
}
