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

import java.util.BitSet;

import org.alfresco.solr.adapters.IOpenBitSet;

/**
 * Pure-Java implementation of {@link IOpenBitSet} backed by {@link java.util.BitSet}.
 *
 * <p>The interface uses {@code long} indices to match Lucene's API, but {@link BitSet}
 * only supports {@code int} indices. In practice, Alfresco node/transaction IDs fit
 * within {@code int} range. An {@link IllegalArgumentException} is thrown if an index
 * exceeds {@link Integer#MAX_VALUE}.
 */
public class JavaBitSetAdapter implements IOpenBitSet
{
    private final BitSet delegate;

    public JavaBitSetAdapter()
    {
        this.delegate = new BitSet();
    }

    private static int toInt(long index)
    {
        if (index < 0 || index > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Index out of int range: " + index);
        }
        return (int) index;
    }

    @Override
    public void set(long index)
    {
        delegate.set(toInt(index));
    }

    @Override
    public boolean get(long index)
    {
        int i = toInt(index);
        return delegate.get(i);
    }

    @Override
    public long nextSetBit(long index)
    {
        int result = delegate.nextSetBit(toInt(index));
        return result; // returns -1 if none found, same contract as Lucene
    }

    @Override
    public long cardinality()
    {
        return delegate.cardinality();
    }

    @Override
    public void or(IOpenBitSet other)
    {
        if (!(other instanceof JavaBitSetAdapter))
        {
            throw new IllegalArgumentException(
                    "Cannot OR with a different IOpenBitSet implementation: " + other.getClass().getName());
        }
        delegate.or(((JavaBitSetAdapter) other).delegate);
    }
}
