/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.zero.metadata;

import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.solr.client.solrj.cloud.BadVersionException;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.Utils;
import org.apache.solr.zero.process.ZeroStoreSolrCloudTestCase;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for {@link ZeroMetadataController} */
public class ZeroMetadataVersionControllerTest extends ZeroStoreSolrCloudTestCase {

  static final String TEST_COLLECTION_NAME = "testCollectionName1";
  static final String TEST_SHARD_NAME = "testShardName";
  static String metadataNodePath;

  static ZeroMetadataController shardMetadataController;
  static SolrCloudManager cloudManager;

  @BeforeClass
  public static void setupCluster() throws Exception {
    assumeWorkingMockito();
    setupCluster(1);

    cloudManager =
        cluster.getJettySolrRunner(0).getCoreContainer().getZkController().getSolrCloudManager();

    // setup a Zero collection
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(TEST_COLLECTION_NAME, 1, 0)
            .setZeroIndex(true)
            .setZeroReplicas(1);
    create.process(cluster.getSolrClient());

    waitForState(
        "Timed-out wait for collection to be created", TEST_COLLECTION_NAME, clusterShape(1, 1));

    shardMetadataController = new ZeroMetadataController(cloudManager);
    metadataNodePath =
        shardMetadataController.getMetadataBasePath(TEST_COLLECTION_NAME, TEST_SHARD_NAME)
            + "/"
            + ZeroMetadataController.SUFFIX_NODE_NAME;

    assumeWorkingMockito();
  }

  @After
  public void cleanup() throws Exception {
    cluster.getZkClient().clean(metadataNodePath);
  }

  @AfterClass
  public static void afterClass() {
    if (shardMetadataController != null) {
      shardMetadataController = null;
    }
    if (cloudManager != null) {
      cloudManager = null;
    }
  }

  /** Test that we create and persist a metadata node */
  @Test
  public void testSetupMetadataNode() throws Exception {
    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));
  }

  /** Test if we try to create the same metadata node twice, overwrite it */
  @Test
  public void testSetupMetadataNodeAlreadyExists() throws Exception {
    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));

    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));
  }

  /**
   * Test that we fail to create the metadata node if we attempt to create it on a collection that
   * is not of type ZERO
   */
  @Test
  public void testSetupMetadataNodeFailsOnNonZeroCollection() throws Exception {
    // setup a non-Zero collection
    String nonZeroCollectionName = "notZeroCollection";
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(nonZeroCollectionName, 1, 1);
    create.process(cluster.getSolrClient());

    waitForState(
        "Timed-out wait for collection to be created", nonZeroCollectionName, clusterShape(1, 1));
    try {
      shardMetadataController.createMetadataNode(nonZeroCollectionName, "notZeroShard");
      fail();
    } catch (SolrException ex) {
      // we should fail
    } catch (Exception ex) {
      fail("Unexpected exception " + ex);
    }
  }

  /*
   * Test that we can update the metadata node without passing a version check value (pass -1)
   */
  @Test
  public void testUpdateMetadataNode() throws Exception {
    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));

    String testMetadataValue = "testValue";
    shardMetadataController.updateMetadataValueWithVersion(
        TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, -1);
    Stat stat = new Stat();
    byte[] data = cluster.getZkClient().getData(metadataNodePath, null, stat, false);

    Map<?, ?> readData = (Map<?, ?>) Utils.fromJSON(data);
    assertEquals(testMetadataValue, readData.get(ZeroMetadataController.SUFFIX_NODE_NAME));
  }

  /*
   * Test that we can update the metadata node passing a version check value and that we fail if the
   * version doesn't match
   */
  @Test
  public void testConditionalUpdateOnMetadataNode() throws Exception {
    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));

    String testMetadataValue = "testValue1";

    // setup with an initial value by writing
    shardMetadataController.updateMetadataValueWithVersion(
        TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, -1);
    Stat stat = new Stat();
    byte[] data = cluster.getZkClient().getData(metadataNodePath, null, stat, false);

    Map<?, ?> readData = (Map<?, ?>) Utils.fromJSON(data);
    assertEquals(testMetadataValue, readData.get(ZeroMetadataController.SUFFIX_NODE_NAME));

    int version = stat.getVersion();
    // try a conditional update that should pass and return a VersionedData instance with
    // the right written value and incremented version
    testMetadataValue = "testValue2";
    ZeroMetadataVersion shardMetadata =
        shardMetadataController.updateMetadataValueWithVersion(
            TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, version);

    // the version monotonically increases, increments on updates. We should expect only one update
    assertEquals(testMetadataValue, shardMetadata.getMetadataSuffix());
    assertEquals(version + 1, shardMetadata.getVersion());

    // try a conditional update that fails with the wrong version number
    try {
      shardMetadataController.updateMetadataValueWithVersion(
          TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, 100);
      fail();
    } catch (SolrException ex) {
      Throwable t = ex.getCause();
      // we should fail specifically for solr's BadVersionException in this test
      assertTrue(t instanceof BadVersionException);
    } catch (Exception ex) {
      fail();
    }
  }

  /** Test reading the metadata node returns the expected value */
  public void testReadMetadataNode() throws Exception {
    shardMetadataController.createMetadataNode(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
    assertTrue(cluster.getZkClient().exists(metadataNodePath, false));

    String testMetadataValue = "testValue1";
    // setup with an initial value by writing
    shardMetadataController.updateMetadataValueWithVersion(
        TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, -1);

    ZeroMetadataVersion shardMetadata =
        shardMetadataController.readMetadataValue(TEST_COLLECTION_NAME, TEST_SHARD_NAME);

    assertEquals(testMetadataValue, shardMetadata.getMetadataSuffix());
  }

  /** Test reading/updating the metadata node when it doesn't exist fails */
  public void testAccessingNonExistentNodeFails() {
    String testMetadataValue = "testValue1";

    try {
      // setup with an initial value by writing
      shardMetadataController.updateMetadataValueWithVersion(
          TEST_COLLECTION_NAME, TEST_SHARD_NAME, testMetadataValue, -1);
      fail();
    } catch (SolrException ex) {
      Throwable t = ex.getCause();
      assertTrue(t instanceof NoSuchElementException);
    }

    try {
      // setup with an initial value by writing
      shardMetadataController.readMetadataValue(TEST_COLLECTION_NAME, TEST_SHARD_NAME);
      fail();
    } catch (SolrException ex) {
      Throwable t = ex.getCause();
      assertTrue(t instanceof NoSuchElementException);
    }
  }
}
