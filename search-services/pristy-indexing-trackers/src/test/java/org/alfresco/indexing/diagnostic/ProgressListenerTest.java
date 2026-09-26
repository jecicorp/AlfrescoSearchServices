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

package org.alfresco.indexing.diagnostic;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ProgressListenerTest
{
    @Test
    public void aKnownBoundIsTheTarget()
    {
        assertEquals(98000L, ProgressListener.target(98000L, 41200L));
    }

    @Test
    public void withoutABoundTheTargetFollowsTheWalk()
    {
        assertEquals(41200L, ProgressListener.target(null, 41200L));
    }

    @Test
    public void thePhasesKeepTheirWireNamesInProcessingOrder()
    {
        assertEquals(List.of("metadata.db", "metadata.index", "acl.db", "acl.index"),
                Arrays.stream(DiagnosticPhase.values()).map(DiagnosticPhase::wireName).toList());
    }
}
