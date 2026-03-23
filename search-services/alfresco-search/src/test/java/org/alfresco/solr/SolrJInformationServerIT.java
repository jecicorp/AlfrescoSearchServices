/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2024 Alfresco Software Limited
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

package org.alfresco.solr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.alfresco.indexing.server.solrj.SolrJInformationServer;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.Transaction;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration tests for {@link SolrJInformationServer} against a real Solr instance.
 *
 * <p>Uses the embedded Solr test infrastructure from {@link AbstractAlfrescoSolrIT}
 * with an {@link EmbeddedSolrServer} as the SolrClient. Since
 * {@code EmbeddedSolrServer} implements {@code SolrClient}, it can be used
 * with {@code SolrJInformationServer} to exercise the indexing, commit, and
 * query operations against a real Solr core with the Alfresco schema.</p>
 *
 * <p>Note: {@code EmbeddedSolrServer} processes requests in-process (no HTTP),
 * but it exercises the full Solr stack including request handlers, update
 * processors, and the index. The {@code /get} real-time get handler may
 * behave differently than with HTTP, so {@code getTrackerInitialState()}
 * tests are limited.</p>
 */
@SolrTestCaseJ4.SuppressSSL
public class SolrJInformationServerIT extends AbstractAlfrescoSolrIT
{
    private static SolrJInformationServer informationServer;
    private static SolrClient solrClient;

    @BeforeClass
    public static void beforeClass() throws Exception
    {
        initAlfrescoCore("schema.xml");

        // Create an EmbeddedSolrServer wrapping the test core.
        // EmbeddedSolrServer implements SolrClient and processes requests in-process.
        // The collection parameter is null because EmbeddedSolrServer targets a specific core.
        solrClient = new EmbeddedSolrServer(getCore());

        // Create SolrJInformationServer with this client
        Properties serverProps = new Properties();
        serverProps.setProperty("alfresco.lag", "1000");
        serverProps.setProperty("alfresco.hole.retention", "3600000");
        informationServer = new SolrJInformationServer(solrClient, null, serverProps, null);
    }

    @Test
    public void testNodeCountInitiallyZero() throws Exception
    {
        // A fresh Solr core should have zero node documents
        long nodeCount = informationServer.nodeCount();
        assertEquals("Node count should be 0 on a fresh core", 0, nodeCount);
    }

    @Test
    public void testGetTrackerInitialState() throws Exception
    {
        TrackerState state = informationServer.getTrackerInitialState();
        assertNotNull("TrackerState must not be null", state);
        // On a fresh core, the last indexed times should be 0
        assertTrue("lastStartTime should be > 0", state.getLastStartTime() > 0);
        assertTrue("timeToStopIndexing should be > 0", state.getTimeToStopIndexing() > 0);
    }

    @Test
    public void testIndexTransactionAndQuery() throws Exception
    {
        long txnId = System.currentTimeMillis();
        long txnCommitTime = System.currentTimeMillis();

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setCommitTimeMs(txnCommitTime);
        txn.setUpdates(1);
        txn.setDeletes(0);

        // Index the transaction
        informationServer.indexTransaction(txn, true);

        // Commit to make it visible
        informationServer.commit(true);

        // Verify the transaction is in the index
        boolean found = informationServer.txnInIndex(txnId, false);
        assertTrue("Transaction " + txnId + " should be in the index after indexing and commit", found);

        // Clean up: delete by transaction ID
        informationServer.deleteByTransactionId(txnId);
        informationServer.commit(true);

        // Verify it's gone
        informationServer.clearProcessedTransactions();
        boolean foundAfterDelete = informationServer.txnInIndex(txnId, false);
        assertFalse("Transaction " + txnId + " should not be in the index after deletion", foundAfterDelete);
    }

    @Test
    public void testIndexAclChangeSetAndQuery() throws Exception
    {
        long aclChangeSetId = System.currentTimeMillis() + 100;
        long aclChangeSetCommitTime = System.currentTimeMillis();

        AclChangeSet changeSet = new AclChangeSet(aclChangeSetId, aclChangeSetCommitTime, 1);

        // Index the ACL change set
        informationServer.indexAclTransaction(changeSet, true);

        // Commit
        informationServer.commit(true);

        // Verify it's in the index
        boolean found = informationServer.aclChangeSetInIndex(aclChangeSetId, false);
        assertTrue("AclChangeSet " + aclChangeSetId + " should be in the index", found);

        // Clean up
        informationServer.deleteByAclChangeSetId(aclChangeSetId);
        informationServer.commit(true);

        informationServer.clearProcessedAclChangeSets();
        boolean foundAfterDelete = informationServer.aclChangeSetInIndex(aclChangeSetId, false);
        assertFalse("AclChangeSet " + aclChangeSetId + " should not be in the index after deletion", foundAfterDelete);
    }

    @Test
    public void testIndexAclReaders() throws Exception
    {
        long aclId = System.currentTimeMillis() + 200;
        long aclChangeSetId = System.currentTimeMillis() + 201;

        List<String> readers = new ArrayList<>();
        readers.add("GROUP_EVERYONE");
        AclReaders aclReaders = new AclReaders(aclId, readers, new ArrayList<>(), aclChangeSetId, "");

        List<AclReaders> aclReadersList = new ArrayList<>();
        aclReadersList.add(aclReaders);

        // Index ACL
        long elapsed = informationServer.indexAcl(aclReadersList, true);
        assertTrue("Elapsed time should be > 0", elapsed > 0);

        // Commit
        informationServer.commit(true);

        // Clean up
        informationServer.deleteByAclId(aclId);
        informationServer.commit(true);
    }

    @Test
    public void testCommitAndRollback() throws Exception
    {
        // Test that commit operations don't throw exceptions
        informationServer.commit();
        informationServer.hardCommit();
        boolean result = informationServer.commit(true);
        assertTrue("commit(true) should return true", result);
    }

    @Test
    public void testGetErrorDocIdsOnFreshCore() throws Exception
    {
        Set<Long> errorDocIds = informationServer.getErrorDocIds();
        assertNotNull("Error doc IDs set must not be null", errorDocIds);
        assertTrue("Error doc IDs should be empty on a fresh core", errorDocIds.isEmpty());
    }

    @Test
    public void testGetCoreStats() throws Exception
    {
        Iterable<Map.Entry<String, Object>> stats = informationServer.getCoreStats();
        assertNotNull("Core stats must not be null", stats);

        boolean hasNodeStat = false;
        for (Map.Entry<String, Object> entry : stats)
        {
            if ("Alfresco Nodes in Index".equals(entry.getKey()))
            {
                hasNodeStat = true;
            }
        }
        assertTrue("Core stats should contain 'Alfresco Nodes in Index'", hasNodeStat);
    }

    @Test
    public void testCapIndex() throws Exception
    {
        // Set a cap
        long capDbid = 999L;
        informationServer.capIndex(capDbid);
        informationServer.commit(true);

        // Query the cap
        long retrievedCap = informationServer.getIndexCap();
        assertEquals("Index cap should match what was set", capDbid, retrievedCap);
    }

    @Test
    public void testGetHoleRetention() throws Exception
    {
        long holeRetention = informationServer.getHoleRetention();
        assertEquals("Hole retention should match configured value", 3600000L, holeRetention);
    }

    @Test
    public void testContinueState() throws Exception
    {
        TrackerState state = informationServer.getTrackerInitialState();
        long originalStartTime = state.getLastStartTime();

        // Small delay to ensure time progresses
        Thread.sleep(50);

        informationServer.continueState(state);

        assertTrue("lastStartTime should be updated after continueState",
                state.getLastStartTime() >= originalStartTime);
        assertTrue("timeToStopIndexing should be > 0 after continueState",
                state.getTimeToStopIndexing() > 0);
    }

    @Test
    public void testCollectionProviderMethods() throws Exception
    {
        // Test that getOpenBitSetInstance and getSimpleOrderedMapInstance work
        assertNotNull("getOpenBitSetInstance must not return null",
                informationServer.getOpenBitSetInstance());
        assertNotNull("getSimpleOrderedMapInstance must not return null",
                informationServer.getSimpleOrderedMapInstance());
    }

    @Test
    public void testCascadeTrackingEnabled() throws Exception
    {
        // Default is true
        assertTrue("Cascade tracking should be enabled by default",
                informationServer.cascadeTrackingEnabled());
    }

    @Test
    public void testDeleteByNodeId() throws Exception
    {
        // Deleting a non-existent node should not throw
        informationServer.deleteByNodeId(999999L);
        informationServer.commit(true);
    }

    @Test
    public void testDeleteByAclId() throws Exception
    {
        // Deleting a non-existent ACL should not throw
        informationServer.deleteByAclId(999999L);
        informationServer.commit(true);
    }
}
