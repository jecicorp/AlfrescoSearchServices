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
package org.alfresco.indexing.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.net.ssl.SSLContext;

import org.alfresco.encryption.KeyStoreParameters;
import org.alfresco.encryption.ssl.SSLEncryptionParameters;
import org.junit.jupiter.api.Test;

class SslParametersFactoryTest
{
    @Test
    void buildsSslContextFromPkcs12() throws Exception
    {
        // Fixtures: src/test/resources/ssl/tracker.p12 + tracker-trust.p12 (password "storepass")
        TrackerProperties.SslConfig ssl = new TrackerProperties.SslConfig();
        ssl.setKeyStore(getClass().getResource("/ssl/tracker.p12").getPath());
        ssl.setKeyStorePassword("storepass");
        ssl.setTrustStore(getClass().getResource("/ssl/tracker-trust.p12").getPath());
        ssl.setTrustStorePassword("storepass");

        SSLContext ctx = SslParametersFactory.toSslContext(ssl);
        assertNotNull(ctx);
        assertNotNull(ctx.getSocketFactory());
    }

    @Test
    void buildsAlfrescoParamsFromPkcs12() throws Exception
    {
        // toAlfrescoParams produces a data-holder for HttpClientFactory (L3, Task 2.3).
        // Passwords are surfaced via JVM system properties so AlfrescoKeyStoreImpl can
        // load them without a metadata side-car file (FileKeyResourceLoader returns empty props).
        TrackerProperties.SslConfig ssl = new TrackerProperties.SslConfig();
        ssl.setKeyStore(getClass().getResource("/ssl/tracker.p12").getPath());
        ssl.setKeyStorePassword("storepass");
        ssl.setTrustStore(getClass().getResource("/ssl/tracker-trust.p12").getPath());
        ssl.setTrustStorePassword("storepass");

        SSLEncryptionParameters params = SslParametersFactory.toAlfrescoParams(ssl);
        assertNotNull(params);
        KeyStoreParameters ksp = params.getKeyStoreParameters();
        KeyStoreParameters tsp = params.getTrustStoreParameters();
        assertNotNull(ksp);
        assertNotNull(tsp);
        // Keystore location is set correctly
        assertNotNull(ksp.getLocation());
        assertNotNull(tsp.getLocation());
        // Password is accessible via the JVM-property key the factory published
        assertNotNull(System.getProperty(ksp.getId() + ".password"));
        assertNotNull(System.getProperty(tsp.getId() + ".password"));
    }
}
