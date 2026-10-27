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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.alfresco.indexing.server.InformationServer;
import org.alfresco.solr.client.TenantDbId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ContentExtractionStrategyTest
{
    @Mock
    private InformationServer infoSrv;

    @Test
    public void aSuccessfulExtractionRepairsTheNode() throws Exception
    {
        TenantDbId docRef = docRef(5L);
        when(infoSrv.updateContent(docRef)).thenReturn(true);

        RepairResult result = new ContentExtractionStrategy(infoSrv).tryRepair(null, docRef);

        assertTrue(result.success());
        assertEquals("CONTENT_EXTRACTION", result.category());
    }

    @Test
    public void aTransformationStillFailingLeavesTheNodePending() throws Exception
    {
        TenantDbId docRef = docRef(5L);
        when(infoSrv.updateContent(docRef)).thenReturn(false);

        assertFalse(new ContentExtractionStrategy(infoSrv).tryRepair(null, docRef).success());
    }

    @Test
    public void anUnreachableRepositoryLeavesTheNodePending() throws Exception
    {
        TenantDbId docRef = docRef(5L);
        when(infoSrv.updateContent(docRef)).thenThrow(new IOException("connection refused"));

        RepairResult result = new ContentExtractionStrategy(infoSrv).tryRepair(null, docRef);

        assertFalse(result.success());
        assertEquals("Error: connection refused", result.message());
    }

    private static TenantDbId docRef(long dbId)
    {
        TenantDbId docRef = new TenantDbId();
        docRef.dbId = dbId;
        docRef.tenant = "";
        return docRef;
    }
}
