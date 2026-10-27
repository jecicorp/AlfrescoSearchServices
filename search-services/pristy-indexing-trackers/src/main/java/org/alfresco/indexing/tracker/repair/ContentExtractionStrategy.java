/*-
 * #%L
 * Pristy Indexing Trackers
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
package org.alfresco.indexing.tracker.repair;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.TenantDbId;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repair strategy for nodes whose content text could not be extracted: fetches it again.
 */
public class ContentExtractionStrategy implements RepairStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentExtractionStrategy.class);
    private static final String CATEGORY = "CONTENT_EXTRACTION";

    private final InformationServer infoSrv;

    public ContentExtractionStrategy(InformationServer infoSrv)
    {
        this.infoSrv = infoSrv;
    }

    @Override
    public String category()
    {
        return CATEGORY;
    }

    @Override
    public RepairResult tryRepair(SolrDocument doc, TenantDbId docRef)
    {
        try
        {
            if (infoSrv.updateContent(docRef))
            {
                return new RepairResult(docRef.dbId, true, CATEGORY, "Content text extracted");
            }
            return new RepairResult(docRef.dbId, false, CATEGORY, "Content transformation still failing");
        }
        catch (Exception e)
        {
            LOG.warn("Error during content extraction of node {}: {}", docRef.dbId, e.getMessage(), e);
            return new RepairResult(docRef.dbId, false, CATEGORY, "Error: " + e.getMessage());
        }
    }
}
