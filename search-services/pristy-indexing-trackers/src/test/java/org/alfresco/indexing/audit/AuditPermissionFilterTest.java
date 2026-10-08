/*-
 * #%L
 * Pristy Indexing Trackers
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
package org.alfresco.indexing.audit;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditPermissionFilterTest
{
    @Test
    public void joinsBothCoresSoDeletionsStayVisible()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(List.of("abeecher"), null);

        assertTrue(f.fq().contains("fromIndex=alfresco"));
        assertTrue(f.fq().contains("fromIndex=archive"));
        assertTrue(f.fq().contains(" OR "));
    }

    @Test
    public void bothJoinsShareOneScopeParameter()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(List.of("abeecher"), null);

        assertEquals(2, countOccurrences(f.fq(), "v=$auditScope"));
        assertTrue(f.params().containsKey("auditScope"));
    }

    @Test
    public void noQuotedStringIsNestedInsideAnother()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(
                List.of("abeecher", "GROUP_EVERYONE"),
                "workspace://SpacesStore/33333333-3333-3333-3333-333333333333");

        for (Map.Entry<String, String> entry : f.params().entrySet())
        {
            assertFalse("nested quoting in " + entry.getKey() + ": " + entry.getValue(),
                    entry.getValue().contains("_query_:"));
        }
        assertFalse(f.fq().contains("READER"));
        assertFalse(f.fq().contains("DENIED"));
    }

    @Test
    public void everyParameterTheScopeReferencesIsSupplied()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(
                List.of("abeecher"), "workspace://SpacesStore/33333333-3333-3333-3333-333333333333");

        for (String name : List.of("auditType", "auditAncestor", "auditReader", "auditDenied"))
        {
            assertTrue("scope references $" + name, f.params().get("auditScope").contains("$" + name));
            assertTrue("missing parameter " + name, f.params().containsKey(name));
        }
        assertTrue(f.params().get("auditReader").contains("v=$auditReaderQuery"));
        assertTrue(f.params().get("auditDenied").contains("v=$auditDeniedQuery"));
        assertTrue(f.params().containsKey("auditReaderQuery"));
        assertTrue(f.params().containsKey("auditDeniedQuery"));
    }

    @Test
    public void putsEveryAuthorityOnBothSidesOfTheFilter()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(
                List.of("abeecher", "GROUP_EVERYONE"), null);

        assertEquals("READER:(\"abeecher\" OR \"GROUP_EVERYONE\")", f.params().get("auditReaderQuery"));
        assertEquals("DENIED:(\"abeecher\" OR \"GROUP_EVERYONE\")", f.params().get("auditDeniedQuery"));
    }

    @Test
    public void pushesTheFolderScopeInsideTheSubquery()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(
                List.of("abeecher"), "workspace://SpacesStore/33333333-3333-3333-3333-333333333333");

        assertEquals("ANCESTOR:\"workspace://SpacesStore/33333333-3333-3333-3333-333333333333\"",
                f.params().get("auditAncestor"));
        assertTrue(f.params().get("auditScope").contains("filter=$auditAncestor"));
        assertFalse(f.fq().contains("ANCESTOR"));
    }

    @Test
    public void omitsTheAncestorClauseWhenThereIsNoFolder()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(List.of("abeecher"), null);

        assertFalse(f.params().containsKey("auditAncestor"));
        assertFalse(f.params().get("auditScope").contains("auditAncestor"));
    }

    @Test
    public void escapesAQuoteInsideAnAuthority()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(List.of("od\"d"), null);

        assertEquals("READER:(\"od\\\"d\")", f.params().get("auditReaderQuery"));
    }

    @Test
    public void escapesABackslashInsideAnAuthority()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(List.of("do\\main"), null);

        assertEquals("READER:(\"do\\\\main\")", f.params().get("auditReaderQuery"));
    }

    @Test
    public void aHostileAuthorityCannotEscapeItsTerm()
    {
        AuditPermissionFilter.Filter f = AuditPermissionFilter.build(
                List.of("x\") OR *:* OR (\"y"), null);

        assertEquals("READER:(\"x\\\") OR *:* OR (\\\"y\")", f.params().get("auditReaderQuery"));
        assertEquals("DENIED:(\"x\\\") OR *:* OR (\\\"y\")", f.params().get("auditDeniedQuery"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAnEmptyAuthoritySet()
    {
        AuditPermissionFilter.build(List.of(), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesANullAuthoritySet()
    {
        AuditPermissionFilter.build(null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesANullAuthority()
    {
        AuditPermissionFilter.build(Arrays.asList("abeecher", null), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesABlankAuthority()
    {
        AuditPermissionFilter.build(Arrays.asList("abeecher", " "), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAnAuthorityHoldingAComma()
    {
        AuditPermissionFilter.build(List.of("GROUP_a,b"), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesABlankFolder()
    {
        AuditPermissionFilter.build(List.of("abeecher"), "  ");
    }

    private static int countOccurrences(String text, String needle)
    {
        int count = 0;
        int from = text.indexOf(needle);
        while (from >= 0)
        {
            count++;
            from = text.indexOf(needle, from + needle.length());
        }
        return count;
    }
}
