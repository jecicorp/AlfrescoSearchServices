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
package org.alfresco.indexing.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses with 401 every request that does not carry the shared secret in {@link #HEADER},
 * except the health probe. Lets everything through when the mode is {@code none}.
 */
public class SharedSecretFilter extends OncePerRequestFilter
{
    public static final String HEADER = "X-Alfresco-Search-Secret";
    public static final String HEALTH_PATH = "/actuator/health";

    private final ServerSecurityProperties properties;

    public SharedSecretFilter(ServerSecurityProperties properties)
    {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException
    {
        if (!properties.isSecretRequired() || HEALTH_PATH.equals(request.getRequestURI()))
        {
            chain.doFilter(request, response);
            return;
        }
        if (!matches(request.getHeader(HEADER)))
        {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String presented)
    {
        if (presented == null || presented.isEmpty())
        {
            return false;
        }
        return MessageDigest.isEqual(
            presented.getBytes(StandardCharsets.UTF_8),
            properties.getSharedSecret().getBytes(StandardCharsets.UTF_8));
    }
}
