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

import java.util.LinkedHashMap;
import java.util.Map;

import org.alfresco.solr.adapters.ISimpleOrderedMap;

/**
 * Pure-Java implementation of {@link ISimpleOrderedMap} backed by a {@link LinkedHashMap}.
 *
 * <p>Preserves insertion order, which matches the semantics of Solr's {@code SimpleOrderedMap}.
 */
public class LinkedHashMapOrderedMap<T> implements ISimpleOrderedMap<T>
{
    private final Map<String, T> delegate = new LinkedHashMap<>();

    @Override
    public void add(String name, T val)
    {
        delegate.put(name, val);
    }

    /**
     * Returns the underlying map (for inspection/testing).
     */
    public Map<String, T> asMap()
    {
        return delegate;
    }
}
