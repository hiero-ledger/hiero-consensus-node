// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.merkle.StateChangeUtils.applyStateChanges;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.QueuePopChange;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.platform.state.QueueState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultMetricsProvider;
import org.hiero.consensus.metrics.platform.prometheus.PrometheusConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateChangeUtilsTest {

    private static final int TEST_STATE_ID = 42;
    private static final int QUEUE_STATE_ID = 100;

    @TempDir
    Path tempDir;

    private MerkleNodeState state;
    private VirtualMap vm;
    private DefaultMetricsProvider metricsProvider;

    @BeforeEach
    void setUp() {
        // Build configuration with required swirlds config classes
        Configuration configuration = ConfigurationBuilder.create()
                // MerkleDB config
                .withConfigDataType(MerkleDbConfig.class)
                // VirtualMap config
                .withConfigDataType(VirtualMapConfig.class)
                // Metrics config classes
                // Common IO config
                .withConfigDataType(TemporaryFileConfig.class)
                // State common config
                .withConfigDataType(StateCommonConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(PrometheusConfig.class)
                // Set config values
                .withValue("merkleDb.databasePath", tempDir.toString())
                .withValue("prometheus.endpointEnabled", "false")
                .build();

        // Create metrics
        metricsProvider = new DefaultMetricsProvider(configuration);
        Metrics metrics = metricsProvider.createGlobalMetrics();
        metricsProvider.start();

        state = new VirtualMapState(configuration, metrics);
        vm = (VirtualMap) state.getRoot();
    }

    @AfterEach
    void tearDown() {
        if (metricsProvider != null) {
            metricsProvider.stop();
        }
    }

    // ========================================
    // Singleton Update Tests
    // ========================================

    @Test
    @DisplayName("Test singleton update with Timestamp value")
    void testSingletonUpdateWithTimestamp() {
        // Create a SingletonUpdateChange with a Timestamp value
        Timestamp timestamp = new Timestamp(1234567890L, 123);
        SingletonUpdateChange singletonChange =
                SingletonUpdateChange.newBuilder().timestampValue(timestamp).build();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .singletonUpdate(singletonChange)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        // Apply the state changes
        applyStateChanges(state, stateChangesBytes);

        // Verify the singleton was stored
        Bytes singletonKey = StateUtils.getStateKeyForSingleton(TEST_STATE_ID);
        Bytes storedValue = state.getBytes(singletonKey);
        assertNotNull(storedValue, "Singleton value should be stored");
    }

    @Test
    @DisplayName("Test singleton update with bytes value")
    void testSingletonUpdateWithBytesValue() {
        Bytes testBytes = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        SingletonUpdateChange singletonChange =
                SingletonUpdateChange.newBuilder().bytesValue(testBytes).build();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .singletonUpdate(singletonChange)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        applyStateChanges(state, stateChangesBytes);

        Bytes singletonKey = StateUtils.getStateKeyForSingleton(TEST_STATE_ID);
        Bytes storedValue = state.getBytes(singletonKey);
        assertNotNull(storedValue, "Singleton value should be stored");
    }

    @Test
    @DisplayName("Test singleton update with entity number value")
    void testSingletonUpdateWithEntityNumber() {
        SingletonUpdateChange singletonChange =
                SingletonUpdateChange.newBuilder().entityNumberValue(999L).build();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .singletonUpdate(singletonChange)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        applyStateChanges(state, stateChangesBytes);

        Bytes singletonKey = StateUtils.getStateKeyForSingleton(TEST_STATE_ID);
        Bytes storedValue = state.getBytes(singletonKey);
        assertNotNull(storedValue, "Singleton value should be stored");
    }

    // ========================================
    // Map Update Tests
    // ========================================

    @Test
    @DisplayName("Test map update with AccountID key and Account value")
    void testMapUpdateWithAccountIdKeyAndAccountValue() {
        AccountID accountId = AccountID.newBuilder()
                .shardNum(0L)
                .realmNum(0L)
                .accountNum(1001L)
                .build();
        Account account = Account.newBuilder()
                .accountId(accountId)
                .alias(Bytes.wrap(new byte[] {10, 20, 30}))
                .build();

        MapChangeKey mapKey = MapChangeKey.newBuilder().accountIdKey(accountId).build();

        MapChangeValue mapValue =
                MapChangeValue.newBuilder().accountValue(account).build();

        MapUpdateChange mapChange = new MapUpdateChange(mapKey, mapValue, false);

        StateChange stateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapChange)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        applyStateChanges(state, stateChangesBytes);

        // Verify the map entry was stored
        assertEquals(sizeBefore + 1, vm.size(), "Map should contain one additional entry");
    }

    @Test
    @DisplayName("Test map update with proto bytes key")
    void testMapUpdateWithProtoBytesKey() {
        Bytes keyBytes = Bytes.wrap(new byte[] {100, 101, 102});

        MapChangeKey mapKey = MapChangeKey.newBuilder().protoBytesKey(keyBytes).build();

        MapChangeValue mapValue =
                MapChangeValue.newBuilder().protoStringValue("test value").build();

        MapUpdateChange mapChange = new MapUpdateChange(mapKey, mapValue, false);

        StateChange stateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapChange)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        applyStateChanges(state, stateChangesBytes);

        assertEquals(sizeBefore + 1, vm.size(), "Map should contain one additional entry");
    }

    @Test
    @DisplayName("Test multiple map updates in a single StateChanges")
    void testMultipleMapUpdates() {
        AccountID accountId1 = AccountID.newBuilder()
                .shardNum(0L)
                .realmNum(0L)
                .accountNum(1001L)
                .build();
        AccountID accountId2 = AccountID.newBuilder()
                .shardNum(0L)
                .realmNum(0L)
                .accountNum(1002L)
                .build();

        Account account1 = Account.newBuilder().accountId(accountId1).build();
        Account account2 = Account.newBuilder().accountId(accountId2).build();

        MapChangeKey mapKey1 =
                MapChangeKey.newBuilder().accountIdKey(accountId1).build();
        MapChangeKey mapKey2 =
                MapChangeKey.newBuilder().accountIdKey(accountId2).build();

        MapChangeValue mapValue1 =
                MapChangeValue.newBuilder().accountValue(account1).build();
        MapChangeValue mapValue2 =
                MapChangeValue.newBuilder().accountValue(account2).build();

        MapUpdateChange mapChange1 = new MapUpdateChange(mapKey1, mapValue1, false);
        MapUpdateChange mapChange2 = new MapUpdateChange(mapKey2, mapValue2, false);

        StateChange stateChange1 = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapChange1)
                .build();

        StateChange stateChange2 = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapChange2)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange1, stateChange2));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        applyStateChanges(state, stateChangesBytes);

        assertEquals(sizeBefore + 2, vm.size(), "Map should contain two additional entries");
    }

    // ========================================
    // Map Delete Tests
    // ========================================

    @Test
    @DisplayName("Test map delete removes entry")
    void testMapDeleteRemovesEntry() {
        // First, add an entry
        AccountID accountId = AccountID.newBuilder()
                .shardNum(0L)
                .realmNum(0L)
                .accountNum(1001L)
                .build();
        Account account = Account.newBuilder().accountId(accountId).build();

        MapChangeKey mapKey = MapChangeKey.newBuilder().accountIdKey(accountId).build();

        MapChangeValue mapValue =
                MapChangeValue.newBuilder().accountValue(account).build();

        MapUpdateChange mapUpdateChange = new MapUpdateChange(mapKey, mapValue, false);

        StateChange updateStateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapUpdateChange)
                .build();

        StateChanges updateStateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(updateStateChange));

        VirtualMap vm = (VirtualMap) state.getRoot();
        long sizeBeforeUpdate = vm.size();
        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(updateStateChanges));
        assertEquals(sizeBeforeUpdate + 1, vm.size(), "Map should contain one additional entry after update");

        // Now delete the entry
        MapDeleteChange mapDeleteChange = new MapDeleteChange(mapKey);

        StateChange deleteStateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapDelete(mapDeleteChange)
                .build();

        StateChanges deleteStateChanges = new StateChanges(new Timestamp(1001L, 0), List.of(deleteStateChange));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(deleteStateChanges));
        assertEquals(sizeBeforeUpdate, vm.size(), "Map should return to original size after delete");
    }

    @Test
    @DisplayName("Test map delete with proto bytes key")
    void testMapDeleteWithProtoBytesKey() {
        // First, add an entry
        Bytes keyBytes = Bytes.wrap(new byte[] {1, 2, 3});

        MapChangeKey mapKey = MapChangeKey.newBuilder().protoBytesKey(keyBytes).build();

        MapChangeValue mapValue =
                MapChangeValue.newBuilder().protoStringValue("test").build();

        MapUpdateChange mapUpdateChange = new MapUpdateChange(mapKey, mapValue, false);

        StateChange updateStateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapUpdate(mapUpdateChange)
                .build();

        StateChanges updateStateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(updateStateChange));

        long sizeBeforeUpdate = vm.size();
        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(updateStateChanges));
        assertEquals(sizeBeforeUpdate + 1, vm.size(), "Map should contain one additional entry after update");

        // Now delete
        MapDeleteChange mapDeleteChange = new MapDeleteChange(mapKey);

        StateChange deleteStateChange = StateChange.newBuilder()
                .stateId(TEST_STATE_ID)
                .mapDelete(mapDeleteChange)
                .build();

        StateChanges deleteStateChanges = new StateChanges(new Timestamp(1001L, 0), List.of(deleteStateChange));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(deleteStateChanges));
        assertEquals(sizeBeforeUpdate, vm.size(), "Map should return to original size after delete");
    }

    // ========================================
    // Queue Push Tests
    // ========================================

    @Test
    @DisplayName("Test queue push adds element to new queue")
    void testQueuePushToNewQueue() {
        Bytes testData = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        QueuePushChange queuePush =
                QueuePushChange.newBuilder().protoBytesElement(testData).build();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePush(queuePush)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        applyStateChanges(state, stateChangesBytes);

        // Verify queue state and element were stored
        // Should have queue state + queue element = 2 additional entries
        assertEquals(sizeBefore + 2, vm.size(), "Should have queue state and one element added");
    }

    @Test
    @DisplayName("Test queue push with string element")
    void testQueuePushWithStringElement() {
        QueuePushChange queuePush = QueuePushChange.newBuilder()
                .protoStringElement("test string element")
                .build();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePush(queuePush)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        applyStateChanges(state, stateChangesBytes);

        assertEquals(sizeBefore + 2, vm.size(), "Should have queue state and one element added");
    }

    @Test
    @DisplayName("Test multiple queue pushes increment tail")
    void testMultipleQueuePushes() {
        // Push the first element
        QueuePushChange queuePush1 = QueuePushChange.newBuilder()
                .protoBytesElement(Bytes.wrap(new byte[] {1}))
                .build();

        StateChange stateChange1 = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePush(queuePush1)
                .build();

        StateChanges stateChanges1 = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange1));

        long sizeBefore = vm.size();
        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges1));
        assertEquals(sizeBefore + 2, vm.size(), "Should have queue state + 1 element");

        // Push the second element
        QueuePushChange queuePush2 = QueuePushChange.newBuilder()
                .protoBytesElement(Bytes.wrap(new byte[] {2}))
                .build();

        StateChange stateChange2 = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePush(queuePush2)
                .build();

        StateChanges stateChanges2 = new StateChanges(new Timestamp(1001L, 0), List.of(stateChange2));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges2));
        assertEquals(sizeBefore + 3, vm.size(), "Should have queue state + 2 elements");

        // Push the third element
        QueuePushChange queuePush3 = QueuePushChange.newBuilder()
                .protoBytesElement(Bytes.wrap(new byte[] {3}))
                .build();

        StateChange stateChange3 = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePush(queuePush3)
                .build();

        StateChanges stateChanges3 = new StateChanges(new Timestamp(1002L, 0), List.of(stateChange3));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges3));
        assertEquals(sizeBefore + 4, vm.size(), "Should have queue state + 3 elements");
    }

    // ========================================
    // Queue Pop Tests
    // ========================================

    @Test
    @DisplayName("Test queue pop removes head element")
    void testQueuePopRemovesHead() throws Exception {
        // First, set up a queue with elements
        Bytes queueStateKey = StateUtils.getStateKeyForSingleton(QUEUE_STATE_ID);
        QueueState initialQueueState = new QueueState(1, 3); // head=1, tail=3 (2 elements)
        state.putBytes(queueStateKey, QueueState.PROTOBUF.toBytes(initialQueueState));

        // Add queue elements at positions 1 and 2
        Bytes element1Key = StateKeyUtils.queueKey(QUEUE_STATE_ID, 1);
        Bytes element2Key = StateKeyUtils.queueKey(QUEUE_STATE_ID, 2);
        state.putBytes(element1Key, Bytes.wrap(new byte[] {10}));
        state.putBytes(element2Key, Bytes.wrap(new byte[] {20}));

        long sizeAfterSetup = vm.size();

        // Pop an element
        QueuePopChange queuePop = new QueuePopChange();

        StateChange stateChange = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePop(queuePop)
                .build();

        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges));

        // Element at the head should be removed, queue state should be updated
        assertEquals(sizeAfterSetup - 1, vm.size(), "Should have one less element after pop");
        assertNull(state.getBytes(element1Key), "Head element should be removed");
        assertNotNull(state.getBytes(element2Key), "Second element should still exist");

        // Verify the queue state was updated
        Bytes newQueueStateBytes = state.getBytes(queueStateKey);
        QueueState newQueueState = QueueState.PROTOBUF.parse(newQueueStateBytes);
        assertEquals(2, newQueueState.head(), "Head should be incremented to 2");
        assertEquals(3, newQueueState.tail(), "Tail should remain at 3");
    }

    @Test
    @DisplayName("Test multiple queue pops")
    void testMultipleQueuePops() throws Exception {
        // Set up a queue with 3 elements
        Bytes queueStateKey = StateUtils.getStateKeyForSingleton(QUEUE_STATE_ID);
        QueueState initialQueueState = new QueueState(1, 4); // head=1, tail=4 (3 elements)
        state.putBytes(queueStateKey, QueueState.PROTOBUF.toBytes(initialQueueState));

        state.putBytes(StateKeyUtils.queueKey(QUEUE_STATE_ID, 1), Bytes.wrap(new byte[] {10}));
        state.putBytes(StateKeyUtils.queueKey(QUEUE_STATE_ID, 2), Bytes.wrap(new byte[] {20}));
        state.putBytes(StateKeyUtils.queueKey(QUEUE_STATE_ID, 3), Bytes.wrap(new byte[] {30}));

        long sizeAfterSetup = vm.size();

        // Pop the first element
        QueuePopChange queuePop1 = new QueuePopChange();
        StateChange stateChange1 = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePop(queuePop1)
                .build();

        StateChanges stateChanges1 = new StateChanges(new Timestamp(1000L, 0), List.of(stateChange1));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges1));
        assertEquals(sizeAfterSetup - 1, vm.size(), "Should have one less element after first pop");

        // Pop the second element
        QueuePopChange queuePop2 = new QueuePopChange();
        StateChange stateChange2 = StateChange.newBuilder()
                .stateId(QUEUE_STATE_ID)
                .queuePop(queuePop2)
                .build();

        StateChanges stateChanges2 = new StateChanges(new Timestamp(1001L, 0), List.of(stateChange2));

        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges2));
        assertEquals(sizeAfterSetup - 2, vm.size(), "Should have two less elements after second pop");

        // Verify the final queue state
        QueueState finalQueueState = QueueState.PROTOBUF.parse(state.getBytes(queueStateKey));
        assertEquals(3, finalQueueState.head(), "Head should be at 3");
        assertEquals(4, finalQueueState.tail(), "Tail should remain at 4");
    }

    // ========================================
    // Combined Operations Tests
    // ========================================

    @Test
    @DisplayName("Test mixed state changes in a single StateChanges")
    void testMixedStateChanges() {
        // Create a singleton update
        SingletonUpdateChange singletonChange =
                SingletonUpdateChange.newBuilder().entityNumberValue(12345L).build();

        StateChange singletonStateChange = StateChange.newBuilder()
                .stateId(1)
                .singletonUpdate(singletonChange)
                .build();

        // Create a map update
        AccountID accountId = AccountID.newBuilder()
                .shardNum(0L)
                .realmNum(0L)
                .accountNum(1001L)
                .build();
        Account account = Account.newBuilder().accountId(accountId).build();
        MapChangeKey mapKey = MapChangeKey.newBuilder().accountIdKey(accountId).build();
        MapChangeValue mapValue =
                MapChangeValue.newBuilder().accountValue(account).build();
        MapUpdateChange mapChange = new MapUpdateChange(mapKey, mapValue, false);

        StateChange mapStateChange =
                StateChange.newBuilder().stateId(2).mapUpdate(mapChange).build();

        // Create a queue push
        QueuePushChange queuePush = QueuePushChange.newBuilder()
                .protoBytesElement(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();

        StateChange queueStateChange =
                StateChange.newBuilder().stateId(3).queuePush(queuePush).build();

        // Apply all changes together
        StateChanges stateChanges = new StateChanges(
                new Timestamp(1000L, 0), List.of(singletonStateChange, mapStateChange, queueStateChange));

        long sizeBefore = vm.size();
        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges));

        // Should have: 1 singleton + 1 map entry + queue state + queue element = 4 additional entries
        assertEquals(sizeBefore + 4, vm.size(), "Should have 4 additional entries");
    }

    @Test
    @DisplayName("Test empty state changes list")
    void testEmptyStateChangesList() {
        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of());

        long sizeBefore = vm.size();
        applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges));

        assertEquals(sizeBefore, vm.size(), "Map size should be unchanged for empty state changes");
    }

    @Test
    @DisplayName("Test state changes with only consensus timestamp")
    void testStateChangesWithOnlyTimestamp() {
        // A StateChanges with just a timestamp and no state changes should be fine
        StateChanges stateChanges = new StateChanges(new Timestamp(1000L, 0), List.of());

        Bytes stateChangesBytes = StateChanges.PROTOBUF.toBytes(stateChanges);

        long sizeBefore = vm.size();
        // Should not throw
        applyStateChanges(state, stateChangesBytes);
        assertEquals(sizeBefore, vm.size(), "Map size should be unchanged");
    }
}
