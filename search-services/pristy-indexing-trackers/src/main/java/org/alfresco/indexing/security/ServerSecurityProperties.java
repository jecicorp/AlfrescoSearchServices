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

import java.util.Locale;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound authentication settings of the trackers HTTP surface.
 * {@code secure-comms} is {@code none} or {@code secret}; in {@code secret} mode every request
 * must present {@code shared-secret} in the {@link SharedSecretFilter#HEADER} header.
 */
@ConfigurationProperties(prefix = "alfresco.tracker.server")
public class ServerSecurityProperties
{
    public static final String MODE_NONE = "none";
    public static final String MODE_SECRET = "secret";

    private String secureComms = MODE_SECRET;
    private String sharedSecret = "";

    public String getSecureComms() { return secureComms; }
    public void setSecureComms(String secureComms)
    {
        this.secureComms = secureComms == null ? null : secureComms.trim().toLowerCase(Locale.ROOT);
    }
    public String getSharedSecret() { return sharedSecret; }
    public void setSharedSecret(String sharedSecret)
    {
        this.sharedSecret = sharedSecret == null ? null : sharedSecret.trim();
    }

    public boolean isNone()
    {
        return MODE_NONE.equals(secureComms);
    }

    @PostConstruct
    void validate()
    {
        if (!MODE_NONE.equals(secureComms) && !MODE_SECRET.equals(secureComms))
        {
            throw new IllegalStateException(
                "alfresco.tracker.server.secure-comms must be 'none' or 'secret', got '"
                    + secureComms + "'");
        }
        if (isNone())
        {
            return;
        }
        if (sharedSecret == null || sharedSecret.isBlank())
        {
            throw new IllegalStateException(
                "alfresco.tracker.server.secure-comms is 'secret' but "
                    + "alfresco.tracker.server.shared-secret is blank");
        }
    }
}
