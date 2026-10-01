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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One index await request: the nodes it still waits for, the transaction each must reach, and its sink.
 */
public final class AwaitHandle
{
    final String core;
    final AwaitSink sink;
    final Set<Long> remaining;
    final Map<Long, Long> requiredTx = new HashMap<>();
    boolean closed;
    private volatile Runnable timeoutCancel = () -> { };

    AwaitHandle(String core, Set<Long> dbids, AwaitSink sink)
    {
        this.core = core;
        this.sink = sink;
        this.remaining = new LinkedHashSet<>(dbids);
    }

    /** @return the core the request waits on */
    public String core()
    {
        return core;
    }

    /** @return where the events of the request go */
    public AwaitSink sink()
    {
        return sink;
    }

    void setTimeoutCancel(Runnable timeoutCancel)
    {
        this.timeoutCancel = timeoutCancel;
    }

    void stopTimeout()
    {
        timeoutCancel.run();
    }
}
