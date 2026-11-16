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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class SharedSecretFilterTest
{
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private SharedSecretFilter filter(String mode)
    {
        ServerSecurityProperties properties = new ServerSecurityProperties();
        properties.setSecureComms(mode);
        properties.setSharedSecret("s3cret");
        return new SharedSecretFilter(properties);
    }

    private void run(String mode, String path, String header) throws Exception
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (header != null)
        {
            request.addHeader(SharedSecretFilter.HEADER, header);
        }
        filter(mode).doFilter(request, response, chain);
    }

    @Test
    public void passesARequestCarryingTheSecret() throws Exception
    {
        run("secret", "/api/v1", "s3cret");
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    public void refusesARequestWithoutTheHeader() throws Exception
    {
        run("secret", "/api/v1", null);
        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    public void refusesARequestWithTheWrongSecret() throws Exception
    {
        run("secret", "/api/v1", "nope");
        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    public void refusesAnEmptyHeaderValue() throws Exception
    {
        run("secret", "/api/v1", "");
        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    public void letsTheHealthProbeThrough() throws Exception
    {
        run("secret", "/actuator/health", null);
        assertNotNull(chain.getRequest());
    }

    @Test
    public void guardsTheOtherActuatorEndpoints() throws Exception
    {
        run("secret", "/actuator/prometheus", null);
        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }

    @Test
    public void passesEverythingWhenSecureCommsIsNone() throws Exception
    {
        run("none", "/api/v1", null);
        assertNotNull(chain.getRequest());
    }

    @Test
    public void guardsWhenSecureCommsIsUnknown() throws Exception
    {
        run("secrt", "/api/v1", null);
        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
    }
}
