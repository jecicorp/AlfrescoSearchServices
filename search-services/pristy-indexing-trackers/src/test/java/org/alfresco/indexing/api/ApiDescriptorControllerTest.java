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

package org.alfresco.indexing.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.alfresco.indexing.config.TrackerProperties;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

public class ApiDescriptorControllerTest
{
    @Test
    public void theDescriptorReportsEveryContributedCapabilitySortedByName()
    {
        ApiDescriptor descriptor = controller(
                List.of(Capability.of("tracker.repair", "1.0"),
                        Capability.of("admin.actions", "1.0"),
                        new Capability("admin.backup", "1.0", false)),
                "9.9.9").describe();

        assertEquals(List.of("admin.actions", "admin.backup", "tracker.repair"),
                new ArrayList<>(descriptor.capabilities().keySet()));
        assertTrue(descriptor.capabilities().get("admin.actions").enabled());
        assertFalse(descriptor.capabilities().get("admin.backup").enabled());
        assertEquals("1.0", descriptor.capabilities().get("tracker.repair").since());
    }

    @Test
    public void theDescriptorCarriesTheServiceAndApiIdentity()
    {
        ApiDescriptor descriptor = controller(List.of(), "1.2.3").describe();

        assertEquals("pristy-indexing-trackers", descriptor.service());
        assertEquals("1", descriptor.api());
        assertEquals("1.2.3", descriptor.version());
    }

    @Test
    public void aBuildCarryingNoVersionIsReportedAsUnknownRatherThanNull()
    {
        ApiDescriptor descriptor = controller(List.of(), null).describe();

        assertEquals("unknown", descriptor.version());
    }

    @Test
    public void aCapabilityTheConfigurationTurnsOffIsAdvertisedAsDisabled()
    {
        TrackerProperties properties = new TrackerProperties();
        properties.setRecordUnindexedNodes(false);

        Capability capability = new ApiCapabilities().unindexedNodesCapability(properties);

        assertEquals("index.unindexed-nodes", capability.name());
        assertFalse(capability.enabled());
    }

    @Test
    public void everyAdvertisedCapabilityIsBackedByABeanOfTheFeatureThatServesIt()
    {
        long declaring = java.util.Arrays.stream(ApiCapabilities.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == Capability.class)
                .count();
        long takingAService = java.util.Arrays.stream(ApiCapabilities.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == Capability.class)
                .filter(method -> method.getParameterCount() > 0)
                .count();

        assertEquals(declaring, takingAService);
    }

    private static ApiDescriptorController controller(List<Capability> capabilities, String version)
    {
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        if (version == null)
        {
            when(provider.getIfAvailable()).thenReturn(null);
        }
        else
        {
            Properties properties = new Properties();
            properties.setProperty("version", version);
            when(provider.getIfAvailable()).thenReturn(new BuildProperties(properties));
        }
        return new ApiDescriptorController(capabilities, provider);
    }
}
