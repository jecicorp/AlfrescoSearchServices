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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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
 *       Passwords are delivered via Alfresco's native password-file mechanism: a temporary
 *       properties file is written for each keystore/truststore, and its path is passed as
 *       the {@code keyMetaDataFileLocation} of {@link KeyStoreParameters}.  No JVM system
 *       properties are set.</li>
 *   <li>{@link #toSslContext(TrackerProperties.SslConfig)} — produces a standard JSSE
 *       {@link SSLContext} for the SolrJ {@code HttpSolrClient} (L2, Task 2.4).</li>
 * </ul>
 */
public final class SslParametersFactory
{
    /**
     * Keystore ID used when building the repository-side {@link SSLEncryptionParameters}.
     * Stable so that AlfrescoKeyStoreImpl can log a meaningful name.
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
     * <p>Passwords are delivered to {@code AlfrescoKeyStoreImpl} via Alfresco's native
     * password-file mechanism rather than JVM system properties.  For each keystore a
     * temporary properties file is written containing the keys expected by
     * {@code AlfrescoKeyStoreImpl.KeyInfoManager.loadKeyMetaData}:
     * <ul>
     *   <li>{@code aliases} — comma-separated list of alias names from the keystore</li>
     *   <li>{@code keystore.password} — the store/key password</li>
     *   <li>{@code <alias>.password} — per-alias key password (same value for PKCS12)</li>
     * </ul>
     * The path of the temp file is passed as the {@code keyMetaDataFileLocation} argument
     * of {@link KeyStoreParameters}.  When that location is non-blank,
     * {@code AlfrescoKeyStoreImpl} delegates to
     * {@link FileKeyResourceLoader#loadKeyMetaData(String)} instead of reading JVM
     * properties.</p>
     *
     * <p>Temp files are created with owner-only read/write permissions (POSIX 0600) and
     * registered for deletion on JVM exit.</p>
     *
     * @param ssl SSL configuration carrying keystore/truststore paths and passwords
     * @return a configured {@link SSLEncryptionParameters}
     * @throws Exception if a keystore cannot be opened or the temp file cannot be written
     */
    public static SSLEncryptionParameters toAlfrescoParams(TrackerProperties.SslConfig ssl)
            throws Exception
    {
        KeyStore keyStore = load(ssl.getKeyStore(), ssl.getKeyStorePassword(), ssl.getKeyStoreType());
        String keyMetaFile = writePasswordFile(keyStore, ssl.getKeyStorePassword(), true);

        KeyStore trustStore = load(ssl.getTrustStore(), ssl.getTrustStorePassword(), ssl.getTrustStoreType());
        String trustMetaFile = writePasswordFile(trustStore, ssl.getTrustStorePassword(), false);

        // Non-blank keyMetaDataFileLocation makes AlfrescoKeyStoreImpl use the file path
        // (via FileKeyResourceLoader.loadKeyMetaData) instead of JVM system properties.
        KeyStoreParameters keyParams = new KeyStoreParameters(
                KEY_STORE_ID,
                "SSL Key Store",
                ssl.getKeyStoreType(),
                null,
                keyMetaFile,
                ssl.getKeyStore());

        KeyStoreParameters trustParams = new KeyStoreParameters(
                TRUST_STORE_ID,
                "SSL Trust Store",
                ssl.getTrustStoreType(),
                null,
                trustMetaFile,
                ssl.getTrustStore());

        return new SSLEncryptionParameters(keyParams, trustParams);
    }

    /**
     * Writes a temporary password-properties file for {@code AlfrescoKeyStoreImpl}.
     *
     * <p>The file contains:
     * <pre>
     * aliases=&lt;comma-separated alias list from the keystore&gt;
     * keystore.password=&lt;storePassword&gt;
     * &lt;alias&gt;.password=&lt;storePassword&gt;   # only when includeKeyPasswords is true
     * </pre>
     * For PKCS12 keystores the key password equals the store password, so
     * {@code includeKeyPasswords} should be {@code true} for key stores and
     * {@code false} for trust stores (which hold only certificates, not keys).</p>
     *
     * <p>The file is created with POSIX permissions 0600 (owner read/write only) and
     * registered for deletion on JVM exit.</p>
     *
     * @param ks                  the keystore whose aliases should be listed
     * @param storePassword       the store (and key) password to write
     * @param includeKeyPasswords whether to write per-alias {@code <alias>.password} lines
     * @return the absolute path of the temp file
     * @throws Exception if the file cannot be created or written
     */
    private static String writePasswordFile(KeyStore ks, String storePassword,
            boolean includeKeyPasswords) throws Exception
    {
        // Collect all aliases into a sorted list for deterministic output.
        List<String> aliases = new ArrayList<>();
        Enumeration<String> en = ks.aliases();
        while (en.hasMoreElements())
        {
            aliases.add(en.nextElement());
        }
        Collections.sort(aliases);

        // Build the properties content expected by AlfrescoKeyStoreImpl.loadKeyMetaData.
        Properties props = new Properties();
        props.setProperty("aliases", String.join(",", aliases));
        props.setProperty("keystore.password", storePassword);
        if (includeKeyPasswords)
        {
            for (String alias : aliases)
            {
                props.setProperty(alias + ".password", storePassword);
            }
        }

        // Write to a temp file with restricted permissions.
        Path tmp = Files.createTempFile("alf-ks-meta-", ".properties");
        tmp.toFile().deleteOnExit();
        setOwnerOnlyPermissions(tmp);
        try (OutputStream out = new FileOutputStream(tmp.toFile()))
        {
            props.store(out, null);
        }
        return tmp.toAbsolutePath().toString();
    }

    /**
     * Restricts {@code path} to owner read/write (POSIX 0600) when the underlying
     * filesystem supports POSIX permissions.  On non-POSIX filesystems this is a no-op.
     */
    private static void setOwnerOnlyPermissions(Path path)
    {
        try
        {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        }
        catch (UnsupportedOperationException | IOException ignored)
        {
            // Non-POSIX filesystem: skip permission setting, best effort only.
        }
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
