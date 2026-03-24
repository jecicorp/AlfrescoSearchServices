/*
 * #%L
 * Alfresco Search Services
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

package org.alfresco.solr.component;

import org.apache.solr.common.params.ShardParams;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

/**
 * Adds consistency information to the search results.
 *
 * When trackers are running in-process (legacy mode), this component retrieved
 * tracker state to report indexing lag. In the new architecture where trackers
 * run as a separate Spring Boot service, this component reports "unknown"
 * consistency state. Clients should query the tracker service directly for
 * indexing status.
 */
public class ConsistencyComponent extends SearchComponent
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Override
    public void prepare(ResponseBuilder rb) throws IOException
    {
        // No preparation required.
    }

    @Override
    public void finishStage(ResponseBuilder rb)
    {
        super.finishStage(rb);
        if (rb.stage != ResponseBuilder.STAGE_GET_FIELDS)
            return;
        try {
            process(rb);
        } catch (IOException e) {
            LOG.error(e.getMessage());
        }
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException
    {
        boolean isShard = rb.req.getParams().getBool(ShardParams.IS_SHARD, false);
        if (!isShard)
        {
            // In the new architecture, trackers run externally.
            // Report unknown consistency state so clients know to check the tracker service.
            rb.rsp.add("lastIndexedTx", -1);
            rb.rsp.add("lastIndexedTxTime", -1);
            rb.rsp.add("txRemaining", -1);
        }
    }

    @Override
    public String getDescription()
    {
        return "Adds consistency information to the search results.";
    }

}
