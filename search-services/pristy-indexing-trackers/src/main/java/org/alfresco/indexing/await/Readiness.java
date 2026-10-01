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

import java.util.List;
import java.util.Optional;

import org.alfresco.indexing.api.NodeIndexStatus.Verdict;

/**
 * Decides from the index documents of a node whether it is searchable, failed, or still pending.
 */
public final class Readiness
{
    private Readiness()
    {
    }

    /**
     * @param dbid       the node DBID
     * @param requiredTx the transaction that last changed the node in the repository
     * @param documents  what the open searcher holds for the node
     * @return the event the node deserves, or empty while the index has not reached {@code requiredTx}
     */
    public static Optional<AwaitEvent> decide(long dbid, long requiredTx, List<IndexedDocument> documents)
    {
        for (IndexedDocument document : documents)
        {
            if (DOC_TYPE_ERROR_NODE.equals(document.docType())
                    && (document.txId() == null || document.txId() >= requiredTx))
            {
                return Optional.of(new AwaitEvent.Failed(dbid, Verdict.ERROR.name()));
            }
        }
        for (IndexedDocument document : documents)
        {
            if (document.txId() == null || document.txId() < requiredTx)
            {
                continue;
            }
            if (DOC_TYPE_NODE.equals(document.docType()))
            {
                return Optional.of(new AwaitEvent.Searchable(dbid));
            }
            if (DOC_TYPE_UNINDEXED_NODE.equals(document.docType()))
            {
                return Optional.of(new AwaitEvent.Failed(dbid, Verdict.UNINDEXED.name()));
            }
        }
        return Optional.empty();
    }
}
