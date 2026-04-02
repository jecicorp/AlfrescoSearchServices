/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2026 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.repo.search.impl.lucene.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/**
 * Ensures token offsets are non-decreasing, as required by Lucene 8+.
 * Alfresco's ML analysis produces tokens for multiple locales whose
 * offsets can go backwards. This filter clamps them to be monotonic.
 */
public final class MonotonicOffsetFilter extends TokenFilter
{
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private int lastStartOffset = 0;

    public MonotonicOffsetFilter(TokenStream input)
    {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException
    {
        if (!input.incrementToken())
        {
            return false;
        }
        int startOff = Math.max(offsetAtt.startOffset(), lastStartOffset);
        int endOff = Math.max(offsetAtt.endOffset(), startOff);
        offsetAtt.setOffset(startOff, endOff);
        lastStartOffset = startOff;
        return true;
    }

    @Override
    public void reset() throws IOException
    {
        super.reset();
        lastStartOffset = 0;
    }
}
