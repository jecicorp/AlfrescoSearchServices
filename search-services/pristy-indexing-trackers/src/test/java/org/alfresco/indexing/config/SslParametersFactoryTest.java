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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
        // Ensure the JVM properties are absent before the call so we can prove they stay absent.
        System.clearProperty("ssl-keystore.password");
        System.clearProperty("ssl-truststore.password");

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

        // Keystore file location must be set.
        assertNotNull(ksp.getLocation());
        assertNotNull(tsp.getLocation());

        // NO JVM system properties must be set — passwords go through the file mechanism.
        assertNull(System.getProperty("ssl-keystore.password"),
                "toAlfrescoParams must NOT set ssl-keystore.password as a JVM system property");
        assertNull(System.getProperty("ssl-truststore.password"),
                "toAlfrescoParams must NOT set ssl-truststore.password as a JVM system property");

        // keyMetaDataFileLocation must point to an existing temp file.
        String keyMetaPath = ksp.getKeyMetaDataFileLocation();
        String trustMetaPath = tsp.getKeyMetaDataFileLocation();
        assertNotNull(keyMetaPath, "keyMetaDataFileLocation must be set for keystore");
        assertNotNull(trustMetaPath, "keyMetaDataFileLocation must be set for truststore");
        assertTrue(Files.exists(Path.of(keyMetaPath)),
                "keystore password file must exist at " + keyMetaPath);
        assertTrue(Files.exists(Path.of(trustMetaPath)),
                "truststore password file must exist at " + trustMetaPath);

        // The keystore password file must contain the expected keys/values.
        Properties keyProps = new Properties();
        try (FileInputStream fis = new FileInputStream(keyMetaPath))
        {
            keyProps.load(fis);
        }
        assertNotNull(keyProps.getProperty("aliases"),
                "keystore password file must contain 'aliases' key");
        assertEquals("storepass", keyProps.getProperty("keystore.password"),
                "keystore.password must equal the configured store password");

        // The truststore password file must contain aliases and keystore.password.
        Properties trustProps = new Properties();
        try (FileInputStream fis2 = new FileInputStream(trustMetaPath))
        {
            trustProps.load(fis2);
        }
        assertNotNull(trustProps.getProperty("aliases"),
                "truststore password file must contain 'aliases' key");
        assertEquals("storepass", trustProps.getProperty("keystore.password"),
                "truststore keystore.password must equal the configured trust store password");
    }
}
