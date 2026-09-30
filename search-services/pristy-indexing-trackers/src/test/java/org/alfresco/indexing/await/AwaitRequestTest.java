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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.alfresco.indexing.config.TrackerProperties;
import org.junit.Test;

public class AwaitRequestTest
{
    @Test
    public void duplicatesAreCollapsedInRequestOrder()
    {
        AwaitRequest.Accepted accepted = new AwaitRequest(List.of(3L, 1L, 3L, 2L), null).accept(30000L, 1000);

        assertEquals(List.of(3L, 1L, 2L), List.copyOf(accepted.dbids()));
    }

    @Test
    public void aMissingTimeoutIsTwentySeconds()
    {
        assertEquals(20000L, new AwaitRequest(List.of(1L), null).accept(30000L, 1000).timeoutMillis());
    }

    @Test
    public void aMissingTimeoutNeverExceedsTheMaximum()
    {
        assertEquals(10000L, new AwaitRequest(List.of(1L), null).accept(10000L, 1000).timeoutMillis());
    }

    @Test
    public void aLongerTimeoutIsCapped()
    {
        assertEquals(30000L, new AwaitRequest(List.of(1L), 999999L).accept(30000L, 1000).timeoutMillis());
    }

    @Test
    public void aShorterTimeoutIsKept()
    {
        assertEquals(5000L, new AwaitRequest(List.of(1L), 5000L).accept(30000L, 1000).timeoutMillis());
    }

    @Test
    public void noDbidsIsRefused()
    {
        assertRefused(new AwaitRequest(null, null), "dbids");
        assertRefused(new AwaitRequest(List.of(), null), "dbids");
    }

    @Test
    public void aDbidThatIsNotPositiveIsRefused()
    {
        assertRefused(new AwaitRequest(List.of(12L, 0L), null), "positive");
        assertRefused(new AwaitRequest(List.of(12L, -3L), null), "positive");
        assertRefused(new AwaitRequest(Arrays.asList(12L, null), null), "positive");
    }

    @Test
    public void moreDbidsThanTheBatchIsRefused()
    {
        InvalidAwaitRequestException refused = assertThrows(InvalidAwaitRequestException.class,
                () -> new AwaitRequest(List.of(1L, 2L, 3L), null).accept(30000L, 2));

        assertEquals("At most 2 DBIDs per request, got 3.", refused.getMessage());
    }

    @Test
    public void duplicatesDoNotCountAgainstTheBatch()
    {
        assertEquals(2, new AwaitRequest(List.of(1L, 1L, 2L), null).accept(30000L, 2).dbids().size());
    }

    @Test
    public void aTimeoutThatIsNotPositiveIsRefused()
    {
        assertRefused(new AwaitRequest(List.of(1L), 0L), "timeout");
        assertRefused(new AwaitRequest(List.of(1L), -5L), "timeout");
    }

    @Test
    public void theAwaitSettingsFollowTheContract()
    {
        TrackerProperties.AwaitConfig defaults = new TrackerProperties().getAwait();

        assertTrue(defaults.isEnabled());
        assertEquals(30000L, defaults.getMaxTimeout());
        assertEquals(1000, defaults.getMaxBatch());
        assertEquals(10000, defaults.getMaxWaiters());
    }

    private static void assertRefused(AwaitRequest request, String reasonFragment)
    {
        InvalidAwaitRequestException refused = assertThrows(InvalidAwaitRequestException.class,
                () -> request.accept(30000L, 1000));
        assertTrue(refused.getMessage(), refused.getMessage().contains(reasonFragment));
    }
}
