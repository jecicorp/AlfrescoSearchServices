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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point of the Pristy indexing API: what this service is and what it can do.
 *
 * <p>A client asks here first. A {@code 200} carries the capabilities it may then use; a
 * {@code 404} means the trackers service predates this API entirely. Neither answer
 * requires the client to know a version number in advance.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiDescriptorController
{
    static final String SERVICE = "pristy-indexing-trackers";
    static final String API_VERSION = "1";
    static final String UNKNOWN_VERSION = "unknown";

    private final List<Capability> capabilities;
    private final ObjectProvider<BuildProperties> buildProperties;

    public ApiDescriptorController(List<Capability> capabilities,
                                   ObjectProvider<BuildProperties> buildProperties)
    {
        this.capabilities = capabilities;
        this.buildProperties = buildProperties;
    }

    @GetMapping
    public ApiDescriptor describe()
    {
        Map<String, ApiDescriptor.State> states = new LinkedHashMap<>();
        capabilities.stream()
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .forEach(capability -> states.put(capability.name(),
                        new ApiDescriptor.State(capability.since(), capability.enabled())));

        return new ApiDescriptor(SERVICE, version(), API_VERSION, states);
    }

    private String version()
    {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null && build.getVersion() != null)
        {
            return build.getVersion();
        }
        String implementation = getClass().getPackage().getImplementationVersion();
        return implementation != null ? implementation : UNKNOWN_VERSION;
    }
}
