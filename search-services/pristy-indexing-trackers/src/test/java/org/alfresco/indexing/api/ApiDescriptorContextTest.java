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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Asserts the descriptor is servable by the real context. {@code TrackerApplicationTest}
 * starts the context lazily, so it never instantiates these beans — only asking for the
 * controller proves that every capability's dependencies can actually be satisfied.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.lazy-initialization=true"}
)
public class ApiDescriptorContextTest
{
    @Autowired
    private ApiDescriptorController controller;

    @Test
    public void everyCapabilityBeanCanBeInstantiated()
    {
        ApiDescriptor descriptor = controller.describe();

        assertEquals(ApiDescriptorController.SERVICE, descriptor.service());
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("index.status"));
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("admin.actions"));
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("admin.backup"));
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("tracker.repair"));
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("index.unindexed-nodes"));
        assertTrue(descriptor.capabilities().keySet().toString(),
                descriptor.capabilities().containsKey("index.progress"));
    }
}
