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

import org.alfresco.test.search.functional.AbstractE2EFunctionalTest;
import org.springframework.beans.factory.annotation.Value;
import org.testng.annotations.BeforeClass;

/**
 * Base class of the e2e tests that call the tracker admin service. It builds {@link #trackerAdmin}
 * from the {@code tracker.*} properties: an mTLS client when {@code tracker.scheme} is https.
 */
public abstract class AbstractTrackerAdminE2ETest extends AbstractE2EFunctionalTest
{
    @Value("${tracker.scheme:http}")
    private String trackerScheme;
    @Value("${tracker.server:localhost}")
    private String trackerServer;
    @Value("${tracker.port:8085}")
    private int trackerPort;
    @Value("${tracker.secret:}")
    private String trackerSecret;
    @Value("${tracker.ssl.keyStorePath:}")
    private String trackerKeyStorePath;
    @Value("${tracker.ssl.keyStorePass:changeit}")
    private String trackerKeyStorePass;
    @Value("${tracker.ssl.trustStorePath:}")
    private String trackerTrustStorePath;
    @Value("${tracker.ssl.trustStorePass:changeit}")
    private String trackerTrustStorePass;

    protected TrackerAdminClient trackerAdmin;

    @BeforeClass(alwaysRun = true)
    public void setupTrackerClient()
    {
        trackerAdmin = TrackerAdminClient.fromSettings(trackerScheme, trackerServer, trackerPort, trackerSecret,
                trackerKeyStorePath, trackerKeyStorePass, trackerTrustStorePath, trackerTrustStorePass);
    }
}
