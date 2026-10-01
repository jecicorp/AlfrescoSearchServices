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

import static org.alfresco.indexing.server.solrj.SolrDocumentMapper.DOC_TYPE_NODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.Test;

public class AwaitRegistryTest
{
    private static final String CORE = "alfresco";
    private static final AwaitSink NOWHERE = event -> { };

    private final List<Delivery> delivered = new ArrayList<>();
    private Consumer<Delivery> outbox = delivered::add;
    private final AwaitRegistry registry = new AwaitRegistry(5, delivery -> outbox.accept(delivery));

    @Test
    public void openingBeyondTheCapacityIsRefused()
    {
        registry.open(CORE, Set.of(1L, 2L, 3L), NOWHERE);

        AwaitCapacityException refused = assertThrows(AwaitCapacityException.class,
                () -> registry.open(CORE, Set.of(4L, 5L, 6L), NOWHERE));

        assertEquals("Too many nodes awaited at once: 3 pending, 3 requested, at most 5.", refused.getMessage());
        assertEquals(3, registry.pending());
    }

    @Test
    public void aSettledNodeFreesItsPlaceAndTheLastOneEndsTheRequest()
    {
        AwaitHandle handle = registry.open(CORE, ordered(1L, 2L), NOWHERE);
        registry.watch(handle, 1L, 10L);
        registry.watch(handle, 2L, 10L);

        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));
        assertEquals(List.of(new AwaitEvent.Searchable(1L)), events(handle));
        assertEquals(1, registry.pending());

        registry.settle(CORE, Map.of(2L, List.of(node(2L, 10L))));
        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.Searchable(2L),
                new AwaitEvent.End(List.of())), events(handle));
        assertEquals(0, registry.pending());
        assertEquals(Set.of(), registry.watched(CORE));
    }

    @Test
    public void aNodeIsReleasedOnlyOnce()
    {
        AwaitHandle handle = registry.open(CORE, ordered(1L, 2L), NOWHERE);
        registry.watch(handle, 1L, 10L);
        registry.watch(handle, 2L, 10L);

        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));
        registry.settle(CORE, Map.of(1L, List.of(node(1L, 11L))));
        registry.fail(handle, 1L, "ORPHAN");

        assertEquals(List.of(new AwaitEvent.Searchable(1L)), events(handle));
    }

    @Test
    public void aNodeIndexedAtAnOlderTransactionStaysPending()
    {
        AwaitHandle handle = registry.open(CORE, Set.of(1L), NOWHERE);
        registry.watch(handle, 1L, 10L);

        registry.settle(CORE, Map.of(1L, List.of(node(1L, 9L))));

        assertEquals(List.of(), events(handle));
        assertEquals(Set.of(1L), registry.watched(CORE));
    }

    @Test
    public void theTimeoutEndsWithWhatIsStillPending()
    {
        AwaitHandle handle = registry.open(CORE, ordered(1L, 2L, 3L), NOWHERE);
        registry.watch(handle, 2L, 10L);
        registry.settle(CORE, Map.of(2L, List.of(node(2L, 10L))));

        registry.expire(handle);

        assertEquals(List.of(new AwaitEvent.Searchable(2L), new AwaitEvent.End(List.of(1L, 3L))), events(handle));
        assertEquals(0, registry.pending());
    }

    @Test
    public void nothingIsDeliveredAfterTheEnd()
    {
        AwaitHandle handle = registry.open(CORE, ordered(1L, 2L), NOWHERE);
        registry.watch(handle, 1L, 10L);
        registry.expire(handle);

        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));
        registry.fail(handle, 2L, "UNREACHABLE");
        registry.expire(handle);

        assertEquals(List.of(new AwaitEvent.End(List.of(1L, 2L))), events(handle));
    }

    @Test
    public void aCancelledRequestGetsNothingAndFreesItsPlace()
    {
        AwaitHandle handle = registry.open(CORE, ordered(1L, 2L), NOWHERE);
        registry.watch(handle, 1L, 10L);

        registry.cancel(handle);
        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));

        assertEquals(List.of(), events(handle));
        assertEquals(0, registry.pending());
        assertFalse(registry.hasWatched(CORE));
    }

    @Test
    public void aFailureReleasesTheNode()
    {
        AwaitHandle handle = registry.open(CORE, Set.of(1L), NOWHERE);

        registry.fail(handle, 1L, "ORPHAN");

        assertEquals(List.of(new AwaitEvent.Failed(1L, "ORPHAN"), new AwaitEvent.End(List.of())), events(handle));
    }

    @Test
    public void watchingAClosedRequestIsIgnored()
    {
        AwaitHandle handle = registry.open(CORE, Set.of(1L), NOWHERE);
        registry.expire(handle);

        registry.watch(handle, 1L, 10L);

        assertEquals(Set.of(), registry.watched(handle));
        assertFalse(registry.hasWatched(CORE));
    }

    @Test
    public void eachRequestWaitsForItsOwnTransaction()
    {
        AwaitHandle first = registry.open(CORE, Set.of(1L), NOWHERE);
        AwaitHandle second = registry.open(CORE, Set.of(1L), NOWHERE);
        registry.watch(first, 1L, 10L);
        registry.watch(second, 1L, 11L);

        assertEquals(Set.of(1L), registry.watched(CORE));
        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));

        assertEquals(List.of(new AwaitEvent.Searchable(1L), new AwaitEvent.End(List.of())), events(first));
        assertEquals(List.of(), events(second));
        assertTrue(registry.hasWatched(CORE));
    }

    @Test
    public void aSinkCancellingDuringTheSettlementIsSafe()
    {
        AwaitHandle first = registry.open(CORE, Set.of(1L), NOWHERE);
        AwaitHandle second = registry.open(CORE, Set.of(1L), NOWHERE);
        registry.watch(first, 1L, 10L);
        registry.watch(second, 1L, 10L);
        outbox = delivery -> {
            delivered.add(delivery);
            registry.cancel(delivery.handle());
        };

        registry.settle(CORE, Map.of(1L, List.of(node(1L, 10L))));

        assertEquals(4, delivered.size());
        assertEquals(0, registry.pending());
    }

    @Test
    public void closingARequestStopsItsTimeout()
    {
        AwaitHandle handle = registry.open(CORE, Set.of(1L), NOWHERE);
        AtomicBoolean stopped = new AtomicBoolean();
        handle.setTimeoutCancel(() -> stopped.set(true));

        registry.fail(handle, 1L, "ORPHAN");

        assertTrue(stopped.get());
    }

    private List<AwaitEvent> events(AwaitHandle handle)
    {
        return delivered.stream().filter(delivery -> delivery.handle() == handle).map(Delivery::event).toList();
    }

    private static Set<Long> ordered(Long... dbids)
    {
        return new LinkedHashSet<>(List.of(dbids));
    }

    private static IndexedDocument node(long dbid, long txId)
    {
        return new IndexedDocument(dbid, DOC_TYPE_NODE, txId);
    }
}
