/*-
 * #%L
 * Search Services E2E Tests
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

package org.alfresco.test.search.functional.searchServices.solr.admin;

import io.restassured.config.SSLConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit coverage of the factories of {@link TrackerAdminClient}. It needs no stack: it
 * asserts what the factory builds, not what a server answers.
 */
public class TrackerAdminClientTest
{
    private static final String KEY_STORE_PATH = "/keystore/trackers.p12";

    private static final String KEY_STORE_PASS = "keypass";

    private static final String TRUST_STORE_PATH = "/keystore/truststore.p12";

    private static final String TRUST_STORE_PASS = "trustpass";

    @Test(description = "The mTLS factory addresses the tracker admin service over https")
    public void buildsAnHttpsBaseUrlFromTheServerAndPort()
    {
        Assert.assertEquals(mtlsClient("secret").baseUrl(), "https://trackers:8085");
    }

    @Test(description = "The mTLS factory never swaps the client keystore with the truststore")
    public void keepsTheKeyStoreAndTheTrustStoreApart()
    {
        SSLConfig sslConfig = mtlsClient("secret").restAssuredConfig().getSSLConfig();

        Assert.assertEquals(sslConfig.getPathToKeyStore(), KEY_STORE_PATH);
        Assert.assertEquals(sslConfig.getKeyStorePassword(), KEY_STORE_PASS);
        Assert.assertEquals(sslConfig.getPathToTrustStore(), TRUST_STORE_PATH);
        Assert.assertEquals(sslConfig.getTrustStorePassword(), TRUST_STORE_PASS);
    }

    @Test(description = "The mTLS factory carries the inbound shared secret")
    public void keepsTheSharedSecret()
    {
        Assert.assertEquals(mtlsClient("s3cr3t").sharedSecret(), "s3cr3t");
    }

    @Test(description = "An http scheme gives the plain client even when keystores are configured")
    public void selectsThePlainClientOverHttp()
    {
        TrackerAdminClient client = fromSettings("http", KEY_STORE_PATH);

        Assert.assertEquals(client.baseUrl(), "http://trackers:8085");
        Assert.assertNull(client.restAssuredConfig());
        Assert.assertEquals(client.sharedSecret(), "secret");
    }

    @Test(description = "An https scheme with a client keystore gives the mTLS client")
    public void selectsTheMtlsClientOverHttpsWithAKeyStore()
    {
        TrackerAdminClient client = fromSettings("https", KEY_STORE_PATH);

        Assert.assertEquals(client.baseUrl(), "https://trackers:8085");
        Assert.assertNotNull(client.restAssuredConfig());
        Assert.assertEquals(client.restAssuredConfig().getSSLConfig().getPathToKeyStore(), KEY_STORE_PATH);
        Assert.assertEquals(client.restAssuredConfig().getSSLConfig().getPathToTrustStore(), TRUST_STORE_PATH);
    }

    @Test(description = "The scheme is compared ignoring case")
    public void ignoresTheSchemeCase()
    {
        Assert.assertNotNull(fromSettings("HTTPS", KEY_STORE_PATH).restAssuredConfig());
    }

    @Test(description = "An https scheme without client keystore gives a client without client certificate")
    public void selectsThePlainClientOverHttpsWithoutAKeyStore()
    {
        for (String keyStorePath : new String[] {null, "", "  "})
        {
            TrackerAdminClient client = fromSettings("https", keyStorePath);

            Assert.assertEquals(client.baseUrl(), "https://trackers:8085");
            Assert.assertNull(client.restAssuredConfig());
        }
    }

    private static TrackerAdminClient fromSettings(String scheme, String keyStorePath)
    {
        return TrackerAdminClient.fromSettings(scheme, "trackers", 8085, "secret", keyStorePath, KEY_STORE_PASS,
                TRUST_STORE_PATH, TRUST_STORE_PASS);
    }

    private static TrackerAdminClient mtlsClient(String sharedSecret)
    {
        return TrackerAdminClient.withMtls("trackers", 8085, KEY_STORE_PATH, KEY_STORE_PASS,
                TRUST_STORE_PATH, TRUST_STORE_PASS, sharedSecret);
    }
}
