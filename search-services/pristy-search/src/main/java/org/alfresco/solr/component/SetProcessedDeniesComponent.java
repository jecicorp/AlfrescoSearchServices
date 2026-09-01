/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
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

import java.io.IOException;
import java.util.Arrays;

import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets a boolean flag ("processedDenies") in the JSON response indicating that
 * the results (should) have been processed with respect to anyDenyDenies
 * (i.e. {@link org.alfresco.solr.query.AbstractQParser} has added the correct clause to the search query).
 * 
 * @author Matt Ward
 */
public class SetProcessedDeniesComponent extends SearchComponent
{
    private static final Logger log = LoggerFactory.getLogger(SetProcessedDeniesComponent.class);

    public static final String PROCESSED_DENIES = "processedDenies";

    @Override
    public void prepare(ResponseBuilder rb) throws IOException
    {
        // No preparation required
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException
    {
        Boolean processedDenies = (Boolean) rb.req.getContext().get(PROCESSED_DENIES);
        processedDenies = (processedDenies == null) ? false : processedDenies;
        if (!processedDenies)
        {
            String[] fqs = rb.req.getParams().getParams("fq");
            log.trace("[ACL-DIAG] processedDenies=false — deny filtering was NOT applied for this request. "
                + "fq params: {}", (fqs != null ? Arrays.toString(fqs) : "null"));
        }
        rb.rsp.add(PROCESSED_DENIES, processedDenies);
    }

    @Override
    public String getDescription()
    {
        return "Adds the processedDenies boolean flag to the search results.";
    }

    
    @Override
    public void finishStage(ResponseBuilder rb) {
      if (rb.stage != ResponseBuilder.STAGE_GET_FIELDS) {
        return;
      }
      
      Boolean processedDenies = (Boolean) rb.req.getContext().get(PROCESSED_DENIES);
      processedDenies = (processedDenies == null) ? false : processedDenies;
      rb.rsp.add(PROCESSED_DENIES, processedDenies);
    }
}
