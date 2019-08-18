/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.apache.kafka.connect.util.clusters.EmbeddedConnectCluster;
import org.apache.kafka.test.IntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests MM2 replication and failover/failback logic.
 *
 * MM2 is configured with active/active replication between two Kafka clusters. Tests validate that
 * records sent to either cluster arrive at the other cluster. Then, a consumer group is migrated from
 * one cluster to the other and back. Tests validate that consumer offsets are translated and replicated
 * between clusters during this failover and failback.
 */
@Category(IntegrationTest.class)
public class MirrorConnectorsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MirrorConnectorsIntegrationTest.class);

    private static final int NUM_RECORDS_PRODUCED = 2000;
    private static final int RECORD_TRANSFER_DURATION_MS = 30000;
    private static final int CHECKPOINT_DURATION_MS = 60000;

    private MirrorMakerConfig mm2Config; 
    private EmbeddedConnectCluster primary;
    private EmbeddedConnectCluster backup;

    @Before
    public void setup() throws IOException {
        Properties brokerProps = new Properties();
        brokerProps.put("auto.create.topics.enable", "false");

        Map<String, String> mm2Props = new HashMap<>();
        mm2Props.put("clusters", "primary, backup");
        mm2Props.put("replication.factor", "1");
        mm2Props.put("max.tasks", "20");
        mm2Props.put("topics", ".*test-topic-.*");
        mm2Props.put("groups", "consumer-group-.*");
        mm2Props.put("primary->backup.enabled", "true");
        mm2Props.put("backup->primary.enabled", "true");
        mm2Props.put("sync.topic.acls.enabled", "false");
        mm2Props.put("emit.checkpoints.interval.seconds", "1");
        mm2Props.put("emit.heartbeats.interval.seconds", "1");
        mm2Props.put("refresh.topics.interval.seconds", "1");
        mm2Props.put("refresh.groups.interval.seconds", "1");
        
        mm2Config = new MirrorMakerConfig(mm2Props); 
        Map<String, String> primaryWorkerProps = mm2Config.workerConfig(new SourceAndTarget("backup", "primary"));
        Map<String, String> backupWorkerProps = mm2Config.workerConfig(new SourceAndTarget("primary", "backup"));

        primary = new EmbeddedConnectCluster.Builder()
                .name("primary-connect-cluster")
                .numWorkers(3)
                .numBrokers(1)
                .brokerProps(brokerProps)
                .workerProps(primaryWorkerProps)
                .build();

        backup = new EmbeddedConnectCluster.Builder()
                .name("backup-connect-cluster")
                .numWorkers(3)
                .numBrokers(1)
                .brokerProps(brokerProps)
                .workerProps(backupWorkerProps)
                .build();

        primary.start();
        backup.start();

        // create these topics before starting the connectors so we don't need to wait for discovery
        primary.kafka().createTopic("test-topic-1", 1);
        primary.kafka().createTopic("backup.test-topic-1", 1);
        primary.kafka().createTopic("heartbeats", 1);
        backup.kafka().createTopic("test-topic-1", 1);
        backup.kafka().createTopic("primary.test-topic-1", 1);
        backup.kafka().createTopic("heartbeats", 1);

        for (int i = 0; i < NUM_RECORDS_PRODUCED; i++) {
            primary.kafka().produce("test-topic-1", 0, "key", "message-1-" + i);
            backup.kafka().produce("test-topic-1", 0, "key", "message-2-" + i);
        }

        // create consumers before starting the connectors so we don't need to wait for discovery
        Consumer<byte[], byte[]> consumer1 = primary.kafka().createConsumerAndSubscribeTo(Collections.singletonMap(
            "group.id", "consumer-group-1"), "test-topic-1", "backup.test-topic-1");
        consumer1.poll(Duration.ofMillis(500));
        consumer1.commitSync();
        consumer1.close();

        Consumer<byte[], byte[]> consumer2 = backup.kafka().createConsumerAndSubscribeTo(Collections.singletonMap(
            "group.id", "consumer-group-1"), "test-topic-1", "primary.test-topic-1");
        consumer2.poll(Duration.ofMillis(500));
        consumer2.commitSync();
        consumer2.close();

        // now that the brokers are running, we can finish setting up the Connectors
        mm2Props.put("primary.bootstrap.servers", primary.kafka().bootstrapServers());
        mm2Props.put("backup.bootstrap.servers", backup.kafka().bootstrapServers());
        mm2Config = new MirrorMakerConfig(mm2Props);

        backup.configureConnector("MirrorSourceConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("primary", "backup"),
            MirrorSourceConnector.class));

        backup.configureConnector("MirrorCheckpointConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("primary", "backup"),
            MirrorCheckpointConnector.class));

        backup.configureConnector("MirrorHeartbeatConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("primary", "backup"),
            MirrorHeartbeatConnector.class));

        primary.configureConnector("MirrorSourceConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("backup", "primary"),
            MirrorSourceConnector.class));

        primary.configureConnector("MirrorCheckpointConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("backup", "primary"),
            MirrorCheckpointConnector.class));

        primary.configureConnector("MirrorHeartbeatConnector", mm2Config.connectorBaseConfig(new SourceAndTarget("backup", "primary"),
            MirrorHeartbeatConnector.class));
    }

    @After
    public void close() throws IOException {
        for (String x : primary.connectors()) {
            primary.deleteConnector(x);
        }
        for (String x : backup.connectors()) {
            backup.deleteConnector(x);
        }
        primary.stop();
        backup.stop();
    }

    @Test
    public void testMirrorConnectors() throws InterruptedException, TimeoutException {
        MirrorClient primaryClient = new MirrorClient(mm2Config.clientConfig("primary"));
        MirrorClient backupClient = new MirrorClient(mm2Config.clientConfig("backup"));

        assertEquals("Records were not produced to primary cluster.", NUM_RECORDS_PRODUCED,
            primary.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "test-topic-1").count());
        assertEquals("Records were not replicated to backup cluster.", NUM_RECORDS_PRODUCED,
            backup.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "primary.test-topic-1").count());
        assertEquals("Records were not produced to backup cluster.", NUM_RECORDS_PRODUCED,
            backup.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "test-topic-1").count());
        assertEquals("Records were not replicated to primary cluster.", NUM_RECORDS_PRODUCED,
            primary.kafka().consume(NUM_RECORDS_PRODUCED, RECORD_TRANSFER_DURATION_MS, "backup.test-topic-1").count());
        assertEquals("Primary cluster doesn't have all records from both clusters.", NUM_RECORDS_PRODUCED * 2,
            primary.kafka().consume(NUM_RECORDS_PRODUCED * 2, RECORD_TRANSFER_DURATION_MS, "backup.test-topic-1", "test-topic-1").count());
        assertEquals("Backup cluster doesn't have all records from both clusters.", NUM_RECORDS_PRODUCED * 2,
            backup.kafka().consume(NUM_RECORDS_PRODUCED * 2, RECORD_TRANSFER_DURATION_MS, "primary.test-topic-1", "test-topic-1").count());
        assertTrue("Heartbeats were not emitted to primary cluster.", primary.kafka().consume(1,
            RECORD_TRANSFER_DURATION_MS, "heartbeats").count() > 0);
        assertTrue("Heartbeats were not emitted to backup cluster.", backup.kafka().consume(1,
            RECORD_TRANSFER_DURATION_MS, "heartbeats").count() > 0);
        assertTrue("Heartbeats were not replicated downstream to backup cluster.", backup.kafka().consume(1,
            RECORD_TRANSFER_DURATION_MS, "primary.heartbeats").count() > 0);
        assertTrue("Heartbeats were not replicated downstream to primary cluster.", primary.kafka().consume(1,
            RECORD_TRANSFER_DURATION_MS, "backup.heartbeats").count() > 0);
        assertTrue("Did not find upstream primary cluster.", backupClient.upstreamClusters().contains("primary"));
        assertEquals("Did not calculate replication hops correctly.", 1, backupClient.replicationHops("primary"));
        assertTrue("Did not find upstream backup cluster.", primaryClient.upstreamClusters().contains("backup"));
        assertEquals("Did not calculate replication hops correctly.", 1, primaryClient.replicationHops("backup"));
        assertTrue("Checkpoints were not emitted downstream to backup cluster.", backup.kafka().consume(1,
            CHECKPOINT_DURATION_MS, "primary.checkpoints.internal").count() > 0);

        Map<TopicPartition, OffsetAndMetadata> backupOffsets = backupClient.remoteConsumerOffsets("consumer-group-1", "primary",
            Duration.ofMillis(CHECKPOINT_DURATION_MS));

        assertTrue("Offsets not translated downstream to backup cluster. Found: " + backupOffsets, backupOffsets.containsKey(
            new TopicPartition("primary.test-topic-1", 0)));

        // Failover consumer group to backup cluster.
        Consumer<byte[], byte[]> consumer1 = backup.kafka().createConsumer(Collections.singletonMap("group.id", "consumer-group-1"));
        consumer1.assign(backupOffsets.keySet());
        backupOffsets.forEach(consumer1::seek);
        consumer1.poll(Duration.ofMillis(500));
        consumer1.commitSync();

        assertTrue("Consumer failedover to zero offset.", consumer1.position(new TopicPartition("primary.test-topic-1", 0)) > 0);
        assertTrue("Consumer failedover beyond expected offset.", consumer1.position(
            new TopicPartition("primary.test-topic-1", 0)) <= NUM_RECORDS_PRODUCED);
        assertTrue("Checkpoints were not emitted upstream to primary cluster.", primary.kafka().consume(1,
            CHECKPOINT_DURATION_MS, "backup.checkpoints.internal").count() > 0);

        consumer1.close();

        Map<TopicPartition, OffsetAndMetadata> primaryOffsets = primaryClient.remoteConsumerOffsets("consumer-group-1", "backup",
            Duration.ofMillis(CHECKPOINT_DURATION_MS));

        assertTrue("Offsets not translated upstream to primary cluster. Found: " + primaryOffsets, primaryOffsets.containsKey(
            new TopicPartition("test-topic-1", 0)));
        assertTrue("Offsets not translated downstream to primary cluster. Found: " + primaryOffsets, primaryOffsets.containsKey(
            new TopicPartition("backup.test-topic-1", 0)));

        // Failback consumer group to primary cluster
        Consumer<byte[], byte[]> consumer2 = primary.kafka().createConsumer(Collections.singletonMap("group.id", "consumer-group-1"));
        consumer2.assign(primaryOffsets.keySet());
        primaryOffsets.forEach(consumer2::seek);
        consumer2.poll(Duration.ofMillis(500));

        assertTrue("Consumer failedback to zero upstream offset.", consumer2.position(new TopicPartition("test-topic-1", 0)) > 0);
        assertTrue("Consumer failedback to zero downstream offset.", consumer2.position(new TopicPartition("backup.test-topic-1", 0)) > 0);
        assertTrue("Consumer failedback beyond expected upstream offset.", consumer2.position(
            new TopicPartition("test-topic-1", 0)) <= NUM_RECORDS_PRODUCED);
        assertTrue("Consumer failedback beyond expected downstream offset.", consumer2.position(
            new TopicPartition("backup.test-topic-1", 0)) <= NUM_RECORDS_PRODUCED);
        
        consumer2.close();
    }
}
