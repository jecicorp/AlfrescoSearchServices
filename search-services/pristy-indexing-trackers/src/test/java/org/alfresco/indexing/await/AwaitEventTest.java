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

import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class AwaitEventTest
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void eachEventCarriesOneLineOfJsonAsTheContractWritesIt() throws Exception
    {
        assertEquals("{\"dbid\":1234}", mapper.writeValueAsString(new AwaitEvent.Searchable(1234L)));
        assertEquals("{\"dbid\":1234,\"verdict\":\"ERROR\"}",
                mapper.writeValueAsString(new AwaitEvent.Failed(1234L, "ERROR")));
        assertEquals("{\"pending\":[1235]}", mapper.writeValueAsString(new AwaitEvent.End(List.of(1235L))));
        assertEquals("{\"pending\":[]}", mapper.writeValueAsString(new AwaitEvent.End(List.of())));
    }

    @Test
    public void theEventNamesAreTheContractOnes()
    {
        assertEquals("searchable", new AwaitEvent.Searchable(1L).eventName());
        assertEquals("error", new AwaitEvent.Failed(1L, "ERROR").eventName());
        assertEquals("end", new AwaitEvent.End(List.of()).eventName());
    }
}
