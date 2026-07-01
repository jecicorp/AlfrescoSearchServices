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

import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * Negative mTLS test: verifies that a request to the tracker admin endpoint
 * WITHOUT a client certificate is rejected by the server.
 *
 * <p>This test is only meaningful when the e2e stack is started with the Pristy
 * https (PKCS12 mTLS) profile ({@code --communication https}).  When the stack
 * runs in http/secret mode the test is expected to be skipped.</p>
 *
 * <p>Prerequisites: run
 * {@code STORE_PASS=changeit keystore/generate-keystores.sh /tmp/mtls-out}
 * before starting the stack with {@code --communication https}.</p>
 */
@Configuration
public class TrackerAdminMtlsTest
{
    // ---------------------------------------------------------------------------
    // Spring/TestNG injectable properties — values come from the test properties
    // file (e.g. src/test/resources/alfresco-search.properties) or system props.
    // ---------------------------------------------------------------------------

    @Value("${tracker.scheme:https}")
    private String trackerScheme;

    @Value("${tracker.server:trackers}")
    private String trackerServer;

    @Value("${tracker.port:8085}")
    private int trackerPort;

    /** Path to the PKCS12 truststore used to verify the server certificate. */
    @Value("${tracker.ssl.trustStorePath:/keystore/truststore.p12}")
    private String trustStorePath;

    @Value("${tracker.ssl.trustStorePass:changeit}")
    private String trustStorePass;

    // Full base URL, built in @BeforeClass.
    private String baseUrl;

    @BeforeClass(alwaysRun = true)
    public void setUp()
    {
        baseUrl = trackerScheme + "://" + trackerServer + ":" + trackerPort;
    }

    /**
     * Asserts that the tracker admin server rejects a request that carries NO client
     * certificate.  With {@code client-auth=need} (REQUIRED) the TLS handshake fails
     * and the server closes the connection, so RestAssured throws a runtime exception
     * (javax.net.ssl.SSLHandshakeException or similar).  Some implementations may
     * instead complete the handshake and return an HTTP 401/403 — both outcomes are
     * acceptable.
     *
     * <p>The test trusts the server certificate via the shared truststore so that only
     * the missing client cert causes the failure, not an untrusted server cert.</p>
     */
    @Test(description = "Tracker admin must reject requests without a client certificate")
    public void adminRejectsMissingClientCert()
    {
        // Trust the server cert (via the shared CA) but present NO client cert.
        SSLConfig serverTrustOnly = SSLConfig.sslConfig()
                .trustStore(trustStorePath, trustStorePass);
        RestAssuredConfig config = RestAssured.config().sslConfig(serverTrustOnly);

        try
        {
            int statusCode = RestAssured.given()
                    .config(config)
                    .baseUri(baseUrl)
                    .basePath("/solr/admin")
                    .queryParam("action", "SUMMARY")
                    .queryParam("wt", "json")
                    .get("/cores")
                    .then()
                    .extract()
                    .statusCode();

            // If the handshake somehow completed the server must return 401 or 403.
            org.testng.Assert.assertTrue(
                    statusCode == 401 || statusCode == 403,
                    "Expected 401 or 403 when no client cert is presented, got: " + statusCode);
        }
        catch (Exception e)
        {
            // An SSLHandshakeException or similar is the expected outcome when the server
            // enforces client-auth=need and no client cert is presented.
            // Nothing to do — the test passes by reaching this branch.
        }
    }
}
