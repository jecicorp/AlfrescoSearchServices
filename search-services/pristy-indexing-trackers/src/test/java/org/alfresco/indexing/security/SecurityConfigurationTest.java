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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

public class SecurityConfigurationTest
{
    @Test
    public void registersTheFilterOnEveryPathAtHighestPrecedence()
    {
        ServerSecurityProperties properties = new ServerSecurityProperties();
        properties.setSecureComms("none");
        FilterRegistrationBean<SharedSecretFilter> registration =
            new SecurityConfiguration().sharedSecretFilterRegistration(properties);

        assertTrue(registration.getUrlPatterns().contains("/*"));
        assertEquals(1, registration.getUrlPatterns().size());
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
    }
}
