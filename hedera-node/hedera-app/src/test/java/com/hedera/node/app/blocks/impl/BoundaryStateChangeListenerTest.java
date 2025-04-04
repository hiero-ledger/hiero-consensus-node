// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_POP;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_PUSH;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.SINGLETON_UPDATE;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.state.recordcache.schemas.V0540RecordCacheSchema.TXN_RECEIPT_QUEUE;
import static com.hedera.node.app.throttle.schemas.V0490CongestionThrottleSchema.THROTTLE_USAGE_SNAPSHOTS_STATE_KEY;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntry;
import com.hedera.hapi.node.state.recordcache.TransactionRecordEntry;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.metrics.StoreMetricsImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoundaryStateChangeListenerTest {
    private static final int STATE_ID = 1;
    private static final int STATE_ID_ENTITY_COUNTS = 41;
    public static final ProtoString PROTO_STRING = new ProtoString("test");
    public static final ProtoBytes PROTO_BYTES = new ProtoBytes(Bytes.wrap(new byte[] {1, 2, 3}));
    public static final EntityCounts ENTITY_COUNTS = EntityCounts.newBuilder()
            .numNfts(1)
            .numAccounts(2)
            .numAirdrops(3)
            .numAliases(4)
            .numTokens(5)
            .numTokenRelations(6)
            .numContractBytecodes(7)
            .numNodes(8)
            .numFiles(9)
            .numSchedules(10)
            .numContractStorageSlots(11)
            .numTopics(12)
            .numStakingInfos(13)
            .build();

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private ConfigProviderImpl configProvider;

    private BoundaryStateChangeListener subject;

    private Configuration configuration = HederaTestConfigBuilder.createConfig();
    private StoreMetricsServiceImpl storeMetricsService = new StoreMetricsServiceImpl(new NoOpMetrics());

    @BeforeEach
    void setUp() {
        subject = new BoundaryStateChangeListener(storeMetricsService, () -> configuration);
    }

    @Test
    void targetTypesAreSingletonAndQueue() {
        assertEquals(Set.of(SINGLETON, QUEUE), subject.stateTypes());
    }

    @Test
    void understandsStateIds() {
        final var service = BlockStreamService.NAME;
        final var stateKey = V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
        assertEquals(stateIdFor(service, stateKey), subject.stateIdFor(service, stateKey));
    }

    @Test
    void testSummarizeCommittedChanges() {
        subject.setBoundaryTimestamp(Instant.now());
        subject.singletonUpdateChange(STATE_ID, "HwService", "Earth", PROTO_STRING);
        BlockItem blockItem = subject.summarizeCommittedChanges();

        assertNotNull(blockItem);
        assertTrue(subject.allStateChanges().isEmpty());
    }

    @Test
    void testAllStateChanges() {
        subject.singletonUpdateChange(STATE_ID, "HwService", "Earth", PROTO_STRING);
        subject.queuePushChange(STATE_ID, "HwService", "Earth", PROTO_BYTES);

        List<StateChange> stateChanges = subject.allStateChanges();
        assertEquals(2, stateChanges.size());
    }

    @Test
    void testQueuePushChange() {
        subject.queuePushChange(STATE_ID, "HwService", "Earth", PROTO_BYTES);

        StateChange stateChange = subject.allStateChanges().getFirst();
        assertEquals(QUEUE_PUSH, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_BYTES.value(), stateChange.queuePush().protoBytesElement());
    }

    @Test
    void testQueuePopChange() {
        subject.queuePopChange(STATE_ID, "HwService", "Earth");

        StateChange stateChange = subject.allStateChanges().getFirst();
        assertEquals(QUEUE_POP, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
    }

    @Test
    void testSingletonUpdateChange() {
        subject.singletonUpdateChange(STATE_ID, "HwService", "Earth", PROTO_STRING);

        StateChange stateChange = subject.allStateChanges().getFirst();
        assertEquals(SINGLETON_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_STRING.value(), stateChange.singletonUpdate().stringValue());
    }

    @Test
    void getAndResetNodeFees() {
        subject.trackCollectedNodeFees(100);
        assertEquals(100, subject.nodeFeesCollected());
    }

    @Test
    void testSingletonUpdateChangeForEntityCounts() {
        subject.singletonUpdateChange(STATE_ID_ENTITY_COUNTS, "HwService", "Earth", ENTITY_COUNTS);

        StateChange stateChange = subject.allStateChanges().getFirst();
        assertEquals(SINGLETON_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID_ENTITY_COUNTS, stateChange.stateId());
        assertEquals(ENTITY_COUNTS, stateChange.singletonUpdate().entityCountsValue());

        final long nodeCapacity = configuration.getConfigData(NodesConfig.class).maxNumber();
        final long topicCapacity =
                configuration.getConfigData(TopicsConfig.class).maxNumber();
        final ContractsConfig contractsConfig = configuration.getConfigData(ContractsConfig.class);
        final long maxSlotStorageCapacity = contractsConfig.maxKvPairsAggregate();
        final long maxContractsCapacity = contractsConfig.maxNumber();
        final long fileCapacity = configuration.getConfigData(FilesConfig.class).maxNumber();
        final long scheduleCapacity =
                configuration.getConfigData(SchedulingConfig.class).maxNumber();
        final long accountsCapacity =
                configuration.getConfigData(AccountsConfig.class).maxNumber();
        final long airdropCapacity =
                configuration.getConfigData(TokensConfig.class).maxAllowedPendingAirdrops();
        final long nftsCapacity =
                configuration.getConfigData(TokensConfig.class).nftsMaxAllowedMints();
        final long maxRels = configuration.getConfigData(TokensConfig.class).maxAggregateRels();
        final long tokenCapacity =
                configuration.getConfigData(TokensConfig.class).maxNumber();

        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.ACCOUNT, accountsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numAccounts());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.AIRDROP, airdropCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numAirdrops());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.NFT, nftsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numNfts());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOKEN, tokenCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTokens());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOKEN_RELATION, maxRels))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTokenRelations());
        assertEquals(
                ((StoreMetricsImpl)
                                storeMetricsService.get(StoreMetricsService.StoreType.CONTRACT, maxContractsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numContractBytecodes());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.NODE, nodeCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numNodes());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.FILE, fileCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numFiles());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.SCHEDULE, scheduleCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numSchedules());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(
                                StoreMetricsService.StoreType.SLOT_STORAGE, maxSlotStorageCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numContractStorageSlots());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOPIC, topicCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTopics());
    }

    private static final ThrottleUsageSnapshot A_SNAPSHOT = ThrottleUsageSnapshot.newBuilder()
            .lastDecisionTime(new Timestamp(1_234, 567))
            .used(890)
            .build();
    private static final ThrottleUsageSnapshot B_SNAPSHOT = ThrottleUsageSnapshot.newBuilder()
            .lastDecisionTime(new Timestamp(2_345, 678))
            .used(901)
            .build();
    private static final ThrottleUsageSnapshot C_SNAPSHOT = ThrottleUsageSnapshot.newBuilder()
            .lastDecisionTime(new Timestamp(3_456, 789))
            .used(12)
            .build();
    private static final ThrottleUsageSnapshots FIRST_SNAPSHOTS =
            new ThrottleUsageSnapshots(List.of(A_SNAPSHOT, B_SNAPSHOT), C_SNAPSHOT);
    private static final ThrottleUsageSnapshots SECOND_SNAPSHOTS =
            new ThrottleUsageSnapshots(List.of(B_SNAPSHOT, C_SNAPSHOT), A_SNAPSHOT);
    private static final ThrottleUsageSnapshots THIRD_SNAPSHOTS =
            new ThrottleUsageSnapshots(List.of(C_SNAPSHOT, A_SNAPSHOT), B_SNAPSHOT);
    private static final TransactionReceiptEntries A_ENTRIES = TransactionReceiptEntries.newBuilder()
            .entries(TransactionReceiptEntry.newBuilder().nodeId(1L).build())
            .build();
    private static final TransactionReceiptEntries B_ENTRIES = TransactionReceiptEntries.newBuilder()
            .entries(TransactionReceiptEntry.newBuilder().nodeId(2L).build())
            .build();
    private static final TransactionReceiptEntries C_ENTRIES = TransactionReceiptEntries.newBuilder()
            .entries(TransactionReceiptEntry.newBuilder().nodeId(3L).build())
            .build();

    @Test
    void deferralAndReplayMechanicsAsExpected() {
        final var state = toyStateWithRecordsBlocksAndThrottles();

        subject.startDeferringCommits();
        final var writableThrottleStates = state.getWritableStates(CongestionThrottleService.NAME);
        final var snapshotState = writableThrottleStates.getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        final var snapshots = List.of(FIRST_SNAPSHOTS, SECOND_SNAPSHOTS, THIRD_SNAPSHOTS);
        for (int i = 0, n = snapshots.size(); i < n; i++) {
            final var snapshot = snapshots.get(i);
            snapshotState.put(snapshot);
            ((CommittableWritableStates) writableThrottleStates).commit();
            assertEquals(snapshot, snapshotState.get());
            // Views of the underlying state don't change until the deferred commits are flushed
            assertEquals(
                    ThrottleUsageSnapshots.DEFAULT,
                    state.getReadableStates(CongestionThrottleService.NAME)
                            .getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY)
                            .get());
        }

        final var writableRecordStates = state.getWritableStates(RecordCacheService.NAME);
        final var receiptsState = writableRecordStates.getQueue(TXN_RECEIPT_QUEUE);
        receiptsState.add(A_ENTRIES);
        receiptsState.add(B_ENTRIES);
        ((CommittableWritableStates) writableRecordStates).commit();
        final var halfwayReceipts = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(receiptsState.iterator(), Spliterator.ORDERED), false)
                .toList();
        assertEquals(List.of(A_ENTRIES, B_ENTRIES), halfwayReceipts);
        receiptsState.poll();
        final var threeQuartersReceipts = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(receiptsState.iterator(), Spliterator.ORDERED), false)
                .toList();
        assertEquals(List.of(B_ENTRIES), threeQuartersReceipts);
        receiptsState.add(C_ENTRIES);
        ((CommittableWritableStates) writableRecordStates).commit();

        final ReadableSingletonState<ThrottleUsageSnapshots> updatedSnapshotState = state.getReadableStates(
                        CongestionThrottleService.NAME)
                .getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        assertEquals(ThrottleUsageSnapshots.DEFAULT, updatedSnapshotState.get());
        final ReadableQueueState<TransactionRecordEntry> updatedReceiptsState =
                state.getReadableStates(RecordCacheService.NAME).getQueue(TXN_RECEIPT_QUEUE);
        assertFalse(updatedReceiptsState.iterator().hasNext());

        subject.flushDeferredCommits(state);

        final ReadableSingletonState<ThrottleUsageSnapshots> finalSnapshotState = state.getReadableStates(
                        CongestionThrottleService.NAME)
                .getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
        assertEquals(THIRD_SNAPSHOTS, finalSnapshotState.get());
        final var receipts = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(receiptsState.iterator(), Spliterator.ORDERED), false)
                .toList();
        assertEquals(List.of(B_ENTRIES, C_ENTRIES), receipts);

        subject.setBoundaryTimestamp(Instant.ofEpochSecond(1_234_567L, 890));
        final var intraBlockChanges =
                subject.summarizeCommittedChanges().stateChangesOrThrow().stateChanges();
        assertEquals(5, intraBlockChanges.size());
    }

    private State toyStateWithRecordsBlocksAndThrottles() {
        final var state = new FakeState();
        state.registerCommitListener(subject);
        final var servicesRegistry = new FakeServicesRegistry();
        Set.of(new RecordCacheService(), new BlockStreamService(), new CongestionThrottleService())
                .forEach(servicesRegistry::register);
        final var migrator = new FakeServiceMigrator();
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        migrator.doMigrations(
                state,
                servicesRegistry,
                null,
                new ServicesSoftwareVersion(
                        bootstrapConfig.getConfigData(VersionConfig.class).servicesVersion()),
                new ConfigProviderImpl().getConfiguration(),
                DEFAULT_CONFIG,
                startupNetworks,
                storeMetricsService,
                configProvider,
                TEST_PLATFORM_STATE_FACADE);
        return state;
    }
}
