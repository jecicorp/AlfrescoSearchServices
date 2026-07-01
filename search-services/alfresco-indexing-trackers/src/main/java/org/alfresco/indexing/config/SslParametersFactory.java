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

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.alfresco.encryption.KeyStoreParameters;
import org.alfresco.encryption.ssl.SSLEncryptionParameters;

/**
 * Builds SSL material for the trackers' outbound clients from {@link TrackerProperties.SslConfig}.
 *
 * <p>Two factory methods are provided:
 * <ul>
 *   <li>{@link #toAlfrescoParams(TrackerProperties.SslConfig)} — produces an
 *       {@link SSLEncryptionParameters} for the Alfresco {@code HttpClientFactory} (L3, Task 2.3).
 *       Passwords are published as JVM system properties so that
 *       {@code AlfrescoKeyStoreImpl} can load them without a metadata side-car file.</li>
 *   <li>{@link #toSslContext(TrackerProperties.SslConfig)} — produces a standard JSSE
 *       {@link SSLContext} for the SolrJ {@code HttpSolrClient} (L2, Task 2.4).</li>
 * </ul>
 */
public final class SslParametersFactory
{
    /** JVM property suffix used by AlfrescoKeyStoreImpl to read the keystore password. */
    private static final String PASSWORD_SUFFIX = ".password";

    /**
     * JVM property suffix used by AlfrescoKeyStoreImpl to read the key alias list.
     * A non-null (but possibly empty) value suppresses the "No aliases" warning.
     */
    private static final String ALIASES_SUFFIX = ".aliases";

    /**
     * Keystore ID used when building the repository-side {@link SSLEncryptionParameters}.
     * Must be unique and stable so JVM properties are predictable.
     */
    private static final String KEY_STORE_ID = "ssl-keystore";

    /**
     * Trust-store ID used when building the repository-side {@link SSLEncryptionParameters}.
     */
    private static final String TRUST_STORE_ID = "ssl-truststore";

    private SslParametersFactory()
    {
    }

    /**
     * Builds an {@link SSLEncryptionParameters} for the Alfresco
     * {@code HttpClientFactory} (L3, repository channel).
     *
     * <p>{@code AlfrescoKeyStoreImpl} reads the keystore password from a JVM system
     * property when the {@code KeyStoreParameters} carries a non-null id and an empty
     * metadata-file location.  This method publishes {@code <id>.password} before
     * returning so that the factory can look it up at SSL initialization time.</p>
     *
     * <p>The actual constructor signature of {@code KeyStoreParameters} (6-arg) is:<br>
     * {@code (String id, String name, String type, String provider,
     *   String keyMetaDataFileLocation, String location)}<br>
     * The 5th argument is a <em>path to a password metadata properties file</em>,
     * not a raw password.  Passing an empty string here triggers the JVM-property
     * lookup path inside {@code AlfrescoKeyStoreImpl.loadKeyMetaData}.</p>
     *
     * @param ssl SSL configuration carrying keystore/truststore paths and passwords
     * @return a configured {@link SSLEncryptionParameters}
     */
    public static SSLEncryptionParameters toAlfrescoParams(TrackerProperties.SslConfig ssl)
    {
        // Publish passwords as JVM system properties so AlfrescoKeyStoreImpl can find them
        // without a metadata side-car file.  The property names follow the
        // "<id>.password" convention defined in AlfrescoKeyStore.KEY_KEYSTORE_PASSWORD.
        System.setProperty(KEY_STORE_ID + PASSWORD_SUFFIX, ssl.getKeyStorePassword());
        System.setProperty(KEY_STORE_ID + ALIASES_SUFFIX, "");
        System.setProperty(TRUST_STORE_ID + PASSWORD_SUFFIX, ssl.getTrustStorePassword());
        System.setProperty(TRUST_STORE_ID + ALIASES_SUFFIX, "");

        // Empty keyMetaDataFileLocation + non-null id → AlfrescoKeyStoreImpl reads password
        // from JVM properties (the branch taken when "secure storage" is not configured).
        KeyStoreParameters keyParams = new KeyStoreParameters(
                KEY_STORE_ID,
                "SSL Key Store",
                ssl.getKeyStoreType(),
                null,
                "",
                ssl.getKeyStore());

        KeyStoreParameters trustParams = new KeyStoreParameters(
                TRUST_STORE_ID,
                "SSL Trust Store",
                ssl.getTrustStoreType(),
                null,
                "",
                ssl.getTrustStore());

        return new SSLEncryptionParameters(keyParams, trustParams);
    }

    /**
     * Builds a standard JSSE {@link SSLContext} for the SolrJ {@code HttpSolrClient} (L2).
     *
     * <p>Both stores are loaded directly via {@link FileInputStream}; no Alfresco
     * abstractions are involved.  The returned context is initialized with the key and
     * trust managers derived from the configured keystores.</p>
     *
     * @param ssl SSL configuration carrying keystore/truststore paths and passwords
     * @return an initialized {@link SSLContext}
     * @throws Exception on keystore load failure or SSL initialization error
     */
    public static SSLContext toSslContext(TrackerProperties.SslConfig ssl) throws Exception
    {
        KeyStore keyStore = load(ssl.getKeyStore(), ssl.getKeyStorePassword(), ssl.getKeyStoreType());
        KeyStore trustStore = load(ssl.getTrustStore(), ssl.getTrustStorePassword(), ssl.getTrustStoreType());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, ssl.getKeyStorePassword().toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static KeyStore load(String location, String password, String type) throws Exception
    {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = new FileInputStream(location))
        {
            ks.load(in, password.toCharArray());
        }
        return ks;
    }
}
