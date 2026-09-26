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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.config.TrackerProperties;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.junit.Test;

public class DiagnosticConfigurationTest
{
    private final TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
    private final TrackerProperties properties = new TrackerProperties();

    @Test
    public void beforeTheTrackersStartTheConfiguredCoresAreDiagnosed()
    {
        properties.getSolr().setCollections(List.of("alfresco", "archive"));

        assertEquals(List.of("alfresco", "archive"), DiagnosticConfiguration.coreNames(bootstrap, properties));
    }

    @Test
    public void onceTheTrackersRunTheRegisteredCoresAreDiagnosedInNameOrder()
    {
        TrackerRegistry registry = mock(TrackerRegistry.class);
        when(registry.getCoreNames()).thenReturn(Set.of("archive", "alfresco", "added"));
        when(bootstrap.getRegistry()).thenReturn(registry);

        assertEquals(List.of("added", "alfresco", "archive"), DiagnosticConfiguration.coreNames(bootstrap, properties));
    }
}
