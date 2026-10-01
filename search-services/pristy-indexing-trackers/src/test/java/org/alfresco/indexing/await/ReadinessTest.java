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

package org.alfresco.indexing.await;

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_ERROR_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_UNINDEXED_NODE;
import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

public class ReadinessTest
{
    @Test
    public void aNodeAtTheRequiredTransactionIsSearchable()
    {
        assertEquals(Optional.of(new AwaitEvent.Searchable(7L)),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_NODE, 10L))));
    }

    @Test
    public void aNodeAtALaterTransactionIsSearchable()
    {
        assertEquals(Optional.of(new AwaitEvent.Searchable(7L)),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_NODE, 12L))));
    }

    @Test
    public void aNodeAtAnEarlierTransactionIsNotYetSearchable()
    {
        assertEquals(Optional.empty(), Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_NODE, 9L))));
    }

    @Test
    public void onlyAFreshErrorNodeIsAnError()
    {
        assertEquals(Optional.of(new AwaitEvent.Failed(7L, "ERROR")),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_ERROR_NODE, 10L))));
    }

    @Test
    public void aFreshErrorNodeWinsOverTheNodeDocument()
    {
        assertEquals(Optional.of(new AwaitEvent.Failed(7L, "ERROR")),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_NODE, 10L),
                        document(DOC_TYPE_ERROR_NODE, 10L))));
    }

    @Test
    public void anErrorFromAnEarlierTransactionDoesNotFailTheNode()
    {
        assertEquals(Optional.empty(), Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_ERROR_NODE, 8L))));
        assertEquals(Optional.of(new AwaitEvent.Searchable(7L)),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_ERROR_NODE, 8L),
                        document(DOC_TYPE_NODE, 10L))));
    }

    @Test
    public void anErrorNodeWithoutTransactionIsAnError()
    {
        assertEquals(Optional.of(new AwaitEvent.Failed(7L, "ERROR")),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_ERROR_NODE, null))));
    }

    @Test
    public void aNodeLeftOutOfTheIndexIsUnindexed()
    {
        assertEquals(Optional.of(new AwaitEvent.Failed(7L, "UNINDEXED")),
                Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_UNINDEXED_NODE, 10L))));
    }

    @Test
    public void anUnindexedNodeFromAnEarlierTransactionIsNotYetDecided()
    {
        assertEquals(Optional.empty(), Readiness.decide(7L, 10L, List.of(document(DOC_TYPE_UNINDEXED_NODE, 9L))));
    }

    @Test
    public void nothingIndexedIsNotYetSearchable()
    {
        assertEquals(Optional.empty(), Readiness.decide(7L, 10L, List.of()));
    }

    private static IndexedDocument document(String docType, Long txId)
    {
        return new IndexedDocument(7L, docType, txId);
    }
}
