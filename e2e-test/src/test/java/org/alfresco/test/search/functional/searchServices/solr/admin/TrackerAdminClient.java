/*
 * #%L
 * Alfresco Search Services E2E Test
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.test.search.functional.searchServices.solr.admin;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.alfresco.rest.core.RestResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for the tracker admin service's Solr-compat endpoint.
 */
public class TrackerAdminClient
{
    private final String baseUrl;

    public TrackerAdminClient(String scheme, String server, int port)
    {
        this.baseUrl = scheme + "://" + server + ":" + port;
    }

    public RestResponse getAction(String action, String... params)
    {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("action", action);
        queryParams.put("wt", "json");

        for (String param : params)
        {
            String[] kv = param.split("=", 2);
            if (kv.length == 2)
            {
                queryParams.put(kv[0], kv[1]);
            }
        }

        Response response = RestAssured.given()
                .baseUri(baseUrl)
                .basePath("/solr/admin")
                .queryParams(queryParams)
                .contentType("application/json")
                .get("/cores")
                .andReturn();

        return new RestResponse(response);
    }
}
