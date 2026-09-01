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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.alfresco.solr.adapters.IOpenBitSet;
import org.junit.Test;

public class JavaBitSetAdapterTest
{
    @Test
    public void testSetAndGet()
    {
        IOpenBitSet bitSet = new JavaBitSetAdapter();
        assertFalse(bitSet.get(42));
        bitSet.set(42);
        assertTrue(bitSet.get(42));
        assertFalse(bitSet.get(41));
    }

    @Test
    public void testCardinality()
    {
        IOpenBitSet bitSet = new JavaBitSetAdapter();
        assertEquals(0, bitSet.cardinality());
        bitSet.set(1);
        bitSet.set(10);
        bitSet.set(100);
        assertEquals(3, bitSet.cardinality());
    }

    @Test
    public void testNextSetBit()
    {
        IOpenBitSet bitSet = new JavaBitSetAdapter();
        bitSet.set(5);
        bitSet.set(10);
        bitSet.set(20);

        assertEquals(5, bitSet.nextSetBit(0));
        assertEquals(5, bitSet.nextSetBit(5));
        assertEquals(10, bitSet.nextSetBit(6));
        assertEquals(20, bitSet.nextSetBit(11));
        assertEquals(-1, bitSet.nextSetBit(21));
    }

    @Test
    public void testOr()
    {
        JavaBitSetAdapter a = new JavaBitSetAdapter();
        a.set(1);
        a.set(3);

        JavaBitSetAdapter b = new JavaBitSetAdapter();
        b.set(2);
        b.set(3);
        b.set(4);

        a.or(b);

        assertTrue(a.get(1));
        assertTrue(a.get(2));
        assertTrue(a.get(3));
        assertTrue(a.get(4));
        assertEquals(4, a.cardinality());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrWithIncompatibleType()
    {
        JavaBitSetAdapter adapter = new JavaBitSetAdapter();
        // Anonymous IOpenBitSet implementation should fail
        adapter.or(new IOpenBitSet()
        {
            @Override public void set(long txid) {}
            @Override public void or(IOpenBitSet other) {}
            @Override public long nextSetBit(long l) { return -1; }
            @Override public long cardinality() { return 0; }
            @Override public boolean get(long i) { return false; }
        });
    }

    @Test
    public void testEmptyBitSet()
    {
        IOpenBitSet bitSet = new JavaBitSetAdapter();
        assertEquals(0, bitSet.cardinality());
        assertEquals(-1, bitSet.nextSetBit(0));
        assertFalse(bitSet.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeIndex()
    {
        IOpenBitSet bitSet = new JavaBitSetAdapter();
        bitSet.set(-1);
    }
}
