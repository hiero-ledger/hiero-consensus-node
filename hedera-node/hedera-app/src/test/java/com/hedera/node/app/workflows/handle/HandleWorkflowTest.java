// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.POST_UPGRADE_WORK;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Collections.emptyIterator;
import static java.util.Collections.emptyList;
import static org.hiero.consensus.platformstate.PlatformStateService.NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.OnProofFinished;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.impl.WrappedRecordBlockHashMigration;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.schedule.ExecutableTxnIterator;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.services.NodeFeeManager;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.throttle.CongestionMetrics;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.app.workflows.handle.record.MigrationRootHashSubmissions;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleWorkflowTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Timestamp BLOCK_TIME = new Timestamp(1_234_567L, 890);

    @Mock
    private HintsService hintsService;

    @Mock
    private EventDescriptorWrapper wrapper;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private BlockHashSigner blockHashSigner;

    @Mock
    private HistoryService historyService;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private StakePeriodChanges stakePeriodChanges;

    @Mock
    private DispatchProcessor dispatchProcessor;

    @Mock
    private StakePeriodManager stakePeriodManager;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BlockRecordManagerImpl blockRecordManager;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private CacheWarmer cacheWarmer;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ImmediateStateChangeListener immediateStateChangeListener;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private OpWorkflowMetrics opWorkflowMetrics;

    @Mock
    private ThrottleServiceManager throttleServiceManager;

    @Mock
    private InitTrigger initTrigger;

    @Mock
    private HollowAccountCompletions hollowAccountCompletions;

    @Mock
    private SystemTransactions systemTransactions;

    @Mock
    private HederaRecordCache recordCache;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private VirtualMapState state;

    @Mock
    private Round round;

    @Mock
    private ConsensusEvent event;

    @Mock
    private StakeInfoHelper stakeInfoHelper;

    @Mock
    private ParentTxnFactory parentTxnFactory;

    @Mock
    private CongestionMetrics congestionMetrics;

    @Mock
    private NodeRewardManager nodeRewardManager;

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private ReadableSingletonState<Object> platformStateReadableSingletonState;

    @Mock
    private PlatformState platformState;

    @Mock
    private NodeFeeManager nodeFeeManager;

    @Mock
    private FileServiceImpl fileService;

    @Mock
    private ServicesRegistry servicesRegistry;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Mock
    private WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration;

    @Mock
    private MigrationRootHashSubmissions migrationRootHashSubmissions;

    @Mock
    private AppContext appContext;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private NodeInfo creatorInfo;

    private HandleWorkflow subject;

    @BeforeEach
    void setUp() {
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableSingletonState singletonState = mock(ReadableSingletonState.class);
        lenient()
                .when(singletonState.get())
                .thenReturn(PlatformState.newBuilder()
                        .creationSoftwareVersion(
                                SemanticVersion.newBuilder().minor(1).build())
                        .build());
        lenient().when(state.getReadableStates(NAME)).thenReturn(readableStates);
        lenient()
                .when(readableStates.getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID))
                .thenReturn(singletonState);

        // Mock BlockInfo readable state needed by handleRound's jumpstart voting check
        final ReadableStates blockRecordReadableStates = mock(ReadableStates.class);
        final ReadableSingletonState<BlockInfo> blockInfoSingleton = mock(ReadableSingletonState.class);
        lenient().when(blockInfoSingleton.get()).thenReturn(BlockInfo.DEFAULT);
        lenient().when(blockRecordReadableStates.getSingleton(BLOCKS_STATE_ID)).thenReturn((ReadableSingletonState)
                blockInfoSingleton);
        lenient().when(state.getReadableStates(BlockRecordService.NAME)).thenReturn(blockRecordReadableStates);
    }

    @Test
    void doesntSkipEventWithMissingCreator() {
        final var presentCreatorId = NodeId.of(1L);
        final var missingCreatorId = NodeId.of(2L);
        final var eventFromPresentCreator = mock(ConsensusEvent.class);
        final var eventFromMissingCreator = mock(ConsensusEvent.class);
        given(round.iterator())
                .willReturn(List.of(eventFromMissingCreator, eventFromPresentCreator)
                        .iterator())
                .willReturn(List.of(eventFromMissingCreator, eventFromPresentCreator)
                        .iterator());
        given(eventFromPresentCreator.getCreatorId()).willReturn(presentCreatorId);
        given(eventFromMissingCreator.getCreatorId()).willReturn(missingCreatorId);
        given(networkInfo.nodeInfo(presentCreatorId.id())).willReturn(mock(NodeInfo.class));
        given(networkInfo.nodeInfo(missingCreatorId.id())).willReturn(null);
        given(eventFromPresentCreator.consensusTransactionIterator()).willReturn(emptyIterator());
        given(eventFromMissingCreator.consensusTransactionIterator()).willReturn(emptyIterator());
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(12345L));
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(NOW);
        given(blockRecordManager.lastIntervalProcessTime()).willReturn(NOW);

        givenSubjectWith(RECORDS, BlockStreamWriterMode.FILE, emptyList());

        subject.handleRound(state, round, txns -> {});

        verify(eventFromPresentCreator).consensusTransactionIterator();
        verify(eventFromMissingCreator).consensusTransactionIterator();
        verify(recordCache).resetRoundReceipts();
        verify(recordCache)
                .commitReceipts(any(), any(), same(immediateStateChangeListener), same(blockStreamManager), any());
    }

    @Test
    void writesEachMigrationStateChangeWithBlockTimestamp() {
        given(round.iterator())
                .willReturn(List.of(event).iterator())
                .willReturn(List.of(event).iterator());
        given(event.allParentsIterator()).willReturn(List.of(wrapper).iterator());
        given(event.getConsensusTimestamp()).willReturn(NOW);
        given(systemTransactions.firstReservedSystemTimeFor(any())).willReturn(NOW);
        final var firstBuilder = StateChanges.newBuilder().stateChanges(List.of(StateChange.DEFAULT));
        final var secondBuilder =
                StateChanges.newBuilder().stateChanges(List.of(StateChange.DEFAULT, StateChange.DEFAULT));
        final var builders = List.of(firstBuilder, secondBuilder);
        givenSubjectWith(BOTH, BlockStreamWriterMode.FILE, builders);

        subject.handleRound(state, round, txns -> {});

        builders.forEach(builder -> verify(blockStreamManager)
                .writeItem(BlockItem.newBuilder()
                        .stateChanges(builder.consensusTimestamp(BLOCK_TIME).build())
                        .build()));
    }

    @Test
    void currentBlockNumberUsesRecordBlockNumberInRecordsMode() throws Exception {
        givenSubjectWith(RECORDS, BlockStreamWriterMode.FILE, emptyList());

        final var method = HandleWorkflow.class.getDeclaredMethod("currentBlockNumber");
        method.setAccessible(true);

        assertNull(method.invoke(subject));
        verify(blockStreamManager, never()).blockNo();
        verify(blockRecordManager, never()).blockNo();
    }

    @Test
    void currentBlockNumberUsesBlockStreamNumberInBlocksMode() throws Exception {
        givenSubjectWith(BLOCKS, BlockStreamWriterMode.FILE, emptyList());
        given(blockStreamManager.blockNo()).willReturn(123L);

        final var method = HandleWorkflow.class.getDeclaredMethod("currentBlockNumber");
        method.setAccessible(true);

        assertEquals(123L, method.invoke(subject));
        verify(blockStreamManager).blockNo();
        verify(blockRecordManager, never()).blockNo();
    }

    @Test
    void postUpgradeRemovesRetiredFeeScheduleFileFromState() {
        final var retiredFileId = FileID.newBuilder().fileNum(111L).build();
        final var simpleFeesFileId = FileID.newBuilder().fileNum(113L).build();
        final var counts =
                new AtomicReference<>(EntityCounts.newBuilder().numFiles(2).build());

        final var entityIdStates = MapWritableStates.builder()
                .state(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID, "ENTITY_ID", () -> EntityNumber.DEFAULT, n -> {}))
                .state(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, "ENTITY_COUNTS", counts::get, counts::set))
                .state(new FunctionWritableSingletonState<>(
                        HIGHEST_NODE_ID_STATE_ID,
                        "HIGHEST_NODE_ID",
                        () -> com.hedera.hapi.platform.state.NodeId.DEFAULT,
                        n -> {}))
                .build();
        final var tokenStates = MapWritableStates.builder()
                .state(MapWritableKVState.builder(STAKING_INFOS_STATE_ID, "STAKING_INFOS")
                        .build())
                .state(new FunctionWritableSingletonState<>(
                        STAKING_NETWORK_REWARDS_STATE_ID,
                        "STAKING_NETWORK_REWARDS",
                        () -> NetworkStakingRewards.DEFAULT,
                        n -> {}))
                .build();
        final var nodeStates = MapWritableStates.builder()
                .state(MapWritableKVState.builder(NODES_STATE_ID, "NODES").build())
                .build();
        final var fileStates = MapWritableStates.builder()
                .state(MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, "FILES")
                        .value(
                                retiredFileId,
                                File.newBuilder().fileId(retiredFileId).build())
                        .value(
                                simpleFeesFileId,
                                File.newBuilder().fileId(simpleFeesFileId).build())
                        .build())
                .build();
        given(state.getWritableStates(TokenService.NAME)).willReturn(tokenStates);
        given(state.getWritableStates(EntityIdService.NAME)).willReturn(entityIdStates);
        given(state.getWritableStates(AddressBookService.NAME)).willReturn(nodeStates);
        given(state.getWritableStates(FileService.NAME)).willReturn(fileStates);
        given(blockStreamManager.pendingWork()).willReturn(POST_UPGRADE_WORK);

        // Same event scaffolding as writeEventHeaderWithNoParentEvents, but carrying one transaction
        // so the workflow reaches the post-upgrade branch of handlePlatformTransaction()
        final var txn = new TransactionWrapper(com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY);
        txn.setConsensusTimestamp(NOW);
        given(event.getHash()).willReturn(CryptoRandomUtils.randomHash());
        given(event.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);
        given(round.iterator()).willAnswer(invocation -> List.of(event).iterator());
        final var creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator())
                .willAnswer(invocation -> List.<ConsensusTransaction>of(txn).iterator());

        givenSubjectWith(StreamMode.BLOCKS, BlockStreamWriterMode.FILE, List.of());

        subject.handleRound(state, round, txns -> {});

        final var files = fileStates.<FileID, File>get(FILES_STATE_ID);
        assertNull(files.get(retiredFileId), "retired fee schedule file must be removed from state");
        assertNotNull(files.get(simpleFeesFileId), "unrelated system files must survive");
        assertEquals(1, counts.get().numFiles(), "file entity counter must be decremented");
    }

    @Test
    void writeEventHeaderWithNoParentEvents() {
        // Setup event with no parents
        given(event.getHash()).willReturn(CryptoRandomUtils.randomHash());
        given(event.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);

        // Set up the round
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Setup node info for event creator
        NodeId creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator())
                .willReturn(List.<ConsensusTransaction>of().iterator());

        // Create subject with BLOCKS mode
        givenSubjectWith(StreamMode.BLOCKS, BlockStreamWriterMode.FILE, List.of());

        // WHEN
        subject.handleRound(state, round, txns -> {});

        // THEN
        verify(blockStreamManager).trackEventHash(event.getHash());

        ArgumentCaptor<BlockItem> blockItemCaptor = ArgumentCaptor.forClass(BlockItem.class);
        verify(blockStreamManager, atLeastOnce()).writeItem(blockItemCaptor.capture());

        // Find the BlockItem that has an event header
        final var eventHeaderItem = blockItemCaptor.getAllValues().stream()
                .filter(BlockItem::hasEventHeader)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No BlockItem with event header found"));

        EventHeader header = eventHeaderItem.eventHeaderOrThrow();
        assertEquals(EventCore.DEFAULT, header.eventCore());
        assertTrue(header.parents().isEmpty());
    }

    @Test
    void writeEventHeaderWithParentEventsInCurrentBlock() {
        // Create event hash and parent hash
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentHash = CryptoRandomUtils.randomHash();

        // Setup parent in current block
        given(blockStreamManager.getEventIndex(parentHash)).willReturn(Optional.of(5)); // Parent is at index 5
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);

        // Setup event with one parent
        EventDescriptorWrapper parent = mock(EventDescriptorWrapper.class);
        given(parent.hash()).willReturn(parentHash);

        given(event.getHash()).willReturn(eventHash);
        given(event.allParentsIterator()).willReturn(List.of(parent).iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);

        // Setup node info for event creator
        NodeId creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());

        // Set up the round
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Create subject with BLOCKS mode
        givenSubjectWith(StreamMode.BLOCKS, BlockStreamWriterMode.FILE, List.of());

        // WHEN
        subject.handleRound(state, round, txns -> {});

        // THEN
        verify(blockStreamManager).trackEventHash(eventHash);

        ArgumentCaptor<BlockItem> blockItemCaptor = ArgumentCaptor.forClass(BlockItem.class);
        verify(blockStreamManager, atLeastOnce()).writeItem(blockItemCaptor.capture());

        // Find the BlockItem that has an event header
        final var eventHeaderItem = blockItemCaptor.getAllValues().stream()
                .filter(BlockItem::hasEventHeader)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No BlockItem with event header found"));

        EventHeader header = eventHeaderItem.eventHeaderOrThrow();

        // Verify parent reference uses index
        assertEquals(1, header.parents().size());
        ParentEventReference parentRef = header.parents().get(0);
        assertTrue(parentRef.hasIndex());
        assertEquals(5, parentRef.indexOrThrow());
        assertFalse(parentRef.hasEventDescriptor());
    }

    @Test
    void writeEventHeaderWithParentEventsNotInCurrentBlock() {
        // Create event hash and parent hash
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentHash = CryptoRandomUtils.randomHash();

        // Setup parent not in current block
        given(blockStreamManager.getEventIndex(parentHash)).willReturn(Optional.empty());
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);

        // Setup event with one parent
        EventDescriptor parentDescriptor = EventDescriptor.newBuilder().build();
        EventDescriptorWrapper parent = mock(EventDescriptorWrapper.class);
        given(parent.hash()).willReturn(parentHash);
        given(parent.toPbj()).willReturn(parentDescriptor);

        given(event.getHash()).willReturn(eventHash);
        given(event.allParentsIterator()).willReturn(List.of(parent).iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);

        // Setup node info for event creator
        NodeId creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());

        // Set up the round
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Create subject with BLOCKS mode
        givenSubjectWith(StreamMode.BLOCKS, BlockStreamWriterMode.FILE, List.of());

        // WHEN
        subject.handleRound(state, round, txns -> {});

        // THEN
        verify(blockStreamManager).trackEventHash(eventHash);

        ArgumentCaptor<BlockItem> blockItemCaptor = ArgumentCaptor.forClass(BlockItem.class);
        verify(blockStreamManager, atLeastOnce()).writeItem(blockItemCaptor.capture());

        // Find the BlockItem that has an event header
        final var eventHeaderItem = blockItemCaptor.getAllValues().stream()
                .filter(BlockItem::hasEventHeader)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No BlockItem with event header found"));

        EventHeader header = eventHeaderItem.eventHeaderOrThrow();

        // Verify parent reference uses full descriptor
        assertEquals(1, header.parents().size());
        ParentEventReference parentRef = header.parents().get(0);
        assertFalse(parentRef.hasIndex());
        assertTrue(parentRef.hasEventDescriptor());
        assertEquals(parentDescriptor, parentRef.eventDescriptorOrThrow());
    }

    @Test
    void writeEventHeaderWithMixedParentEvents() {
        // Create event hash and parent hashes
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentInBlockHash = CryptoRandomUtils.randomHash();
        Hash parentNotInBlockHash = CryptoRandomUtils.randomHash();

        // Setup parents - one in block, one not in block
        given(blockStreamManager.getEventIndex(parentInBlockHash)).willReturn(Optional.of(3));
        given(blockStreamManager.getEventIndex(parentNotInBlockHash)).willReturn(Optional.empty());
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);

        // Setup descriptors for parents
        EventDescriptor notInBlockDescriptor = EventDescriptor.newBuilder().build();

        // Setup parent wrappers
        EventDescriptorWrapper parentInBlock = mock(EventDescriptorWrapper.class);
        given(parentInBlock.hash()).willReturn(parentInBlockHash);

        EventDescriptorWrapper parentNotInBlock = mock(EventDescriptorWrapper.class);
        given(parentNotInBlock.hash()).willReturn(parentNotInBlockHash);
        given(parentNotInBlock.toPbj()).willReturn(notInBlockDescriptor);

        // Setup event with two parents
        given(event.getHash()).willReturn(eventHash);
        given(event.allParentsIterator())
                .willReturn(List.of(parentInBlock, parentNotInBlock).iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);

        // Setup node info for event creator
        NodeId creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());

        // Set up the round
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Create subject with BLOCKS mode
        givenSubjectWith(StreamMode.BLOCKS, BlockStreamWriterMode.FILE, List.of());

        // WHEN
        subject.handleRound(state, round, txns -> {});

        // THEN
        verify(blockStreamManager).trackEventHash(eventHash);

        ArgumentCaptor<BlockItem> blockItemCaptor = ArgumentCaptor.forClass(BlockItem.class);
        verify(blockStreamManager, atLeastOnce()).writeItem(blockItemCaptor.capture());

        // Find the BlockItem that has an event header
        final var eventHeaderItem = blockItemCaptor.getAllValues().stream()
                .filter(BlockItem::hasEventHeader)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No BlockItem with event header found"));

        EventHeader header = eventHeaderItem.eventHeaderOrThrow();

        // Verify parent references - one index, one descriptor
        assertEquals(2, header.parents().size());

        ParentEventReference inBlockRef = header.parents().get(0);
        assertTrue(inBlockRef.hasIndex());
        assertEquals(3, inBlockRef.indexOrThrow());
        assertFalse(inBlockRef.hasEventDescriptor());

        ParentEventReference notInBlockRef = header.parents().get(1);
        assertFalse(notInBlockRef.hasIndex());
        assertTrue(notInBlockRef.hasEventDescriptor());
        assertEquals(notInBlockDescriptor, notInBlockRef.eventDescriptorOrThrow());
    }

    private void givenSubjectWith(
            @NonNull final StreamMode mode,
            @NonNull BlockStreamWriterMode streamWriterMode,
            @NonNull final List<StateChanges.Builder> migrationStateChanges) {
        givenSubjectWith(mode, streamWriterMode, migrationStateChanges, Map.of(), 1);
    }

    private void givenSubjectWith(
            @NonNull final StreamMode mode,
            @NonNull final BlockStreamWriterMode streamWriterMode,
            @NonNull final List<StateChanges.Builder> migrationStateChanges,
            @NonNull final Map<String, String> configOverrides) {
        givenSubjectWith(mode, streamWriterMode, migrationStateChanges, configOverrides, 1);
    }

    private void givenSubjectWith(
            @NonNull final StreamMode mode,
            @NonNull final BlockStreamWriterMode streamWriterMode,
            @NonNull final List<StateChanges.Builder> migrationStateChanges,
            @NonNull final Map<String, String> configOverrides,
            final int txnOffsetNanos) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "" + mode)
                .withValue("blockStream.writerMode", "" + streamWriterMode)
                .withValue("tss.hintsEnabled", "false")
                .withValue("tss.historyEnabled", "false");
        configOverrides.forEach(config::withValue);
        final var hederaConfig = config.getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(hederaConfig, 1L));
        lenient().when(round.getConsensusTimestamp()).thenReturn(NOW);
        subject = new HandleWorkflow(
                networkInfo,
                stakePeriodChanges,
                dispatchProcessor,
                configProvider,
                blockRecordManager,
                blockStreamManager,
                cacheWarmer,
                opWorkflowMetrics,
                throttleServiceManager,
                initTrigger,
                hollowAccountCompletions,
                systemTransactions,
                stakeInfoHelper,
                recordCache,
                exchangeRateManager,
                stakePeriodManager,
                migrationStateChanges,
                parentTxnFactory,
                boundaryStateChangeListener,
                immediateStateChangeListener,
                scheduleService,
                hintsService,
                historyService,
                congestionMetrics,
                () -> PlatformStatus.ACTIVE,
                blockHashSigner,
                null,
                nodeRewardManager,
                blockBufferService,
                Map.of(),
                quiescenceController,
                nodeFeeManager,
                txnOffsetNanos);
    }

    private void givenFreezeRoundPlatformState() {
        final var readableStates = mock(ReadableStates.class);
        final ReadableSingletonState<PlatformState> readableSingletonState = mock(ReadableSingletonState.class);
        final var writableStates = mock(WritableStates.class);
        final WritableSingletonState<PlatformState> writableSingletonState = mock(WritableSingletonState.class);
        final var freezeState = PlatformState.newBuilder()
                .creationSoftwareVersion(SemanticVersion.newBuilder().minor(1).build())
                .freezeTime(new Timestamp(NOW.getEpochSecond() - 1, NOW.getNano()))
                .build();

        given(state.getReadableStates(NAME)).willReturn(readableStates);
        given(readableStates.getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID))
                .willReturn((ReadableSingletonState) readableSingletonState);
        given(readableSingletonState.get()).willReturn(freezeState);

        given(state.getWritableStates(NAME)).willReturn(writableStates);
        given(writableStates.getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID))
                .willReturn((WritableSingletonState) writableSingletonState);
        given(writableSingletonState.get()).willReturn(freezeState);
    }

    @Test
    void startRoundShouldCallEnsureNewBlocksPermitted() {
        // Mock the round iterator and event
        final NodeId creatorId = NodeId.of(0);
        final Hash eventHash = CryptoRandomUtils.randomHash();
        given(event.getHash()).willReturn(eventHash);
        given(event.getCreatorId()).willReturn(creatorId);
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);
        given(event.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Create subject with streamToBlockNodes enabled
        givenSubjectWith(BOTH, BlockStreamWriterMode.FILE_AND_GRPC, emptyList());

        subject.handleRound(state, round, txn -> {});

        verify(blockBufferService).ensureNewBlocksPermitted();
    }

    @Test
    void suppressesDuplicateTssReconcileErrorsUntilReset() {
        givenSubjectWith(BOTH, BlockStreamWriterMode.FILE, emptyList());
        final var logCaptor = new LogCaptor(LogManager.getLogger(HandleWorkflow.class));

        try {
            invokeLogTssReconcileFailure(duplicateTssReconcileFailure());
            invokeLogTssReconcileFailure(duplicateTssReconcileFailure());

            assertEquals(1, logCaptor.errorLogs().size());
            assertTrue(logCaptor.errorLogs().get(0).contains("trying to reconcile TSS state"));

            invokeResetTssReconcileFailureSuppression();
            invokeLogTssReconcileFailure(duplicateTssReconcileFailure());

            assertEquals(2, logCaptor.errorLogs().size());
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void logsDifferentTssReconcileErrorsIndependently() {
        givenSubjectWith(BOTH, BlockStreamWriterMode.FILE, emptyList());
        final var logCaptor = new LogCaptor(LogManager.getLogger(HandleWorkflow.class));

        try {
            invokeLogTssReconcileFailure(duplicateTssReconcileFailure());
            invokeLogTssReconcileFailure(differentTssReconcileFailure());

            assertEquals(2, logCaptor.errorLogs().size());
        } finally {
            logCaptor.stopCapture();
        }
    }

    private void givenPositiveFreezeRound() {
        given(platformStateReadableSingletonState.get()).willReturn(platformState);
        given(platformState.latestFreezeRound()).willReturn(10L);
    }

    private void invokeLogTssReconcileFailure(@NonNull final Exception e) {
        try {
            final var method = HandleWorkflow.class.getDeclaredMethod("logTssReconcileFailure", Exception.class);
            method.setAccessible(true);
            method.invoke(subject, e);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private void invokeResetTssReconcileFailureSuppression() {
        try {
            final var method = HandleWorkflow.class.getDeclaredMethod("resetTssReconcileFailureSuppression");
            method.setAccessible(true);
            method.invoke(subject);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static NullPointerException duplicateTssReconcileFailure() {
        return new NullPointerException("boom");
    }

    private static IllegalStateException differentTssReconcileFailure() {
        return new IllegalStateException("boom");
    }

    @Test
    void freezeRoundSkipsWrappedHashWritesInBlocksMode() {
        final var freezeEvent = mock(ConsensusEvent.class);
        final var creatorId = NodeId.of(0);
        given(round.iterator()).willAnswer(ignore -> List.of(freezeEvent).iterator());
        given(freezeEvent.getCreatorId()).willReturn(creatorId);
        given(freezeEvent.getConsensusTimestamp()).willReturn(NOW);
        given(freezeEvent.getHash()).willReturn(CryptoRandomUtils.randomHash());
        given(freezeEvent.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(freezeEvent.getEventCore()).willReturn(EventCore.DEFAULT);
        given(freezeEvent.consensusTransactionIterator()).willReturn(emptyIterator());
        givenFreezeRoundPlatformState();
        givenSubjectWith(
                BLOCKS,
                BlockStreamWriterMode.FILE,
                emptyList(),
                Map.of(
                        "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true",
                        "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true"),
                1);

        subject.handleRound(state, round, txns -> {});

        verify(blockRecordManager, never()).endRound(state);
        verify(blockRecordManager, never()).closeCurrentRecordFileIfOpen(state);
    }

    /**
     * The {@code nextTime} for the first scheduled transaction must equal
     * {@code lastUsedConsensusTime + txnOffsetNanos}. This test uses {@code txnOffsetNanos = 104}
     * (= reservedSystemTxnNanos=100 + maxPrecedingRecords=3 + 1) and confirms that
     * {@code StakePeriodManager.setCurrentStakePeriodFor} — called at the top of the scheduling
     * loop with {@code nextTime} — receives exactly {@code NOW.plusNanos(104)}.
     *
     * <p>Under the old formula, {@code nextTime = lastTime + (maxPrecedingRecords + 1) = NOW + 4},
     * so a failure here would point to a regression to the old calculation.
     */
    @Test
    void scheduledTxnNextTimeUsesTxnOffsetNanos() {
        final var creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(round.iterator()).willAnswer(ignore -> List.of(event).iterator());

        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(NOW);
        // EPOCH causes executionStart to be set to consensusNow, keeping the window simple
        given(blockRecordManager.lastIntervalProcessTime()).willReturn(Instant.EPOCH);
        // lastTime = NOW → nextTime = NOW + txnOffsetNanos
        given(blockRecordManager.lastUsedConsensusTime()).willReturn(NOW);

        // Minimal state setup for WritableEntityIdStoreImpl construction in executeAsManyScheduled.
        // The entity-id states must implement CommittableWritableStates so the cast in
        // doStreamingChangesInternal succeeds; getSingleton returns null (acceptable since the
        // store's internals are never accessed — the scheduleService mock ignores the StoreFactory).
        final var entityIdStates =
                mock(WritableStates.class, withSettings().extraInterfaces(CommittableWritableStates.class));
        lenient().when(state.getWritableStates(EntityIdService.NAME)).thenReturn(entityIdStates);
        lenient().when(state.getWritableStates(ScheduleService.NAME)).thenReturn(mock(WritableStates.class));

        // Iterator reports one pending txn; iter.next() returns null which triggers an NPE inside
        // executeScheduled — caught by executeScheduledTransactions — after setCurrentStakePeriodFor
        // has already been called with nextTime.
        final var schedIter = mock(ExecutableTxnIterator.class);
        given(schedIter.hasNext()).willReturn(true);
        given(scheduleService.executableTxns(any(), any(), any())).willReturn(schedIter);

        // txnOffsetNanos=104; with defaults consTimeSeparationNanos=1000 and maxFollowingRecords=50:
        // lastUsableTime = NOW + (1000 - 50 - 104) = NOW + 846, which is comfortably above nextTime.
        givenSubjectWith(
                RECORDS, BlockStreamWriterMode.FILE, emptyList(), Map.of("scheduling.longTermEnabled", "true"), 104);

        subject.handleRound(state, round, txns -> {});

        // Old formula: NOW + (maxPrecedingRecords + 1) = NOW + 4
        // New formula: NOW + txnOffsetNanos = NOW + 104
        verify(stakePeriodManager).setCurrentStakePeriodFor(eq(NOW.plusNanos(104)));
    }

    /**
     * When {@code txnOffsetNanos} is large enough that
     * {@code nextTime = lastUsedConsensusTime + txnOffsetNanos} exceeds
     * {@code lastUsableTime = consensusNow + (consTimeSeparationNanos - maxFollowingRecords - txnOffsetNanos)},
     * the scheduling loop must not execute any transactions at all.
     *
     * <p>Here {@code txnOffsetNanos = 951} with defaults
     * {@code consTimeSeparationNanos=1000, maxFollowingRecords=50} gives
     * {@code lastUsableTime = NOW - 1}, which is strictly before {@code nextTime = NOW + 951}.
     */
    @Test
    void lastUsableTimePreventsScheduledDispatchWhenOffsetTooLarge() {
        final var creatorId = NodeId.of(0);
        given(event.getCreatorId()).willReturn(creatorId);
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(round.iterator()).willAnswer(ignore -> List.of(event).iterator());

        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(NOW);
        given(blockRecordManager.lastIntervalProcessTime()).willReturn(Instant.EPOCH);
        given(blockRecordManager.lastUsedConsensusTime()).willReturn(NOW);

        final var entityIdStates =
                mock(WritableStates.class, withSettings().extraInterfaces(CommittableWritableStates.class));
        lenient().when(state.getWritableStates(EntityIdService.NAME)).thenReturn(entityIdStates);
        lenient().when(state.getWritableStates(ScheduleService.NAME)).thenReturn(mock(WritableStates.class));

        final var schedIter = mock(ExecutableTxnIterator.class);
        given(schedIter.hasNext()).willReturn(true);
        //        given(schedIter.purgeUntilNext()).willReturn(false);
        given(scheduleService.executableTxns(any(), any(), any())).willReturn(schedIter);

        // txnOffsetNanos=951; lastUsableTime = NOW + (1000 - 50 - 951) = NOW - 1 < nextTime = NOW + 951
        givenSubjectWith(
                RECORDS, BlockStreamWriterMode.FILE, emptyList(), Map.of("scheduling.longTermEnabled", "true"), 951);

        subject.handleRound(state, round, txns -> {});

        // The iterator was obtained (confirms we reached executeAsManyScheduled)
        verify(scheduleService).executableTxns(any(), any(), any());
        // But the loop body never entered — no scheduled txn was started
        verify(stakePeriodManager, never()).setCurrentStakePeriodFor(any());
    }

    @Test
    void ledgerIdExternalizationDoesNotReuseTheNodeFeeConsensusTime() {
        final var beforeEvents = Instant.ofEpochSecond(1_234_600L, 100);
        final var afterEvents = beforeEvents.plusSeconds(1);
        final var ledgerId = Bytes.wrap("LEDGER_ID");
        final var proof = HistoryProof.newBuilder()
                .targetHistory(History.newBuilder().addressBookHash(ledgerId).build())
                .build();
        final var construction = HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .targetProof(proof)
                .build();
        final var historyStore = mock(WritableHistoryStore.class);
        given(historyStore.getActiveConstruction()).willReturn(construction);
        // Stand in for a finished proof; in production the callback fires from the controller, either while
        // reconciling TSS state or while handling the HistoryProofVote that completes the construction
        willAnswer(invocation -> {
                    invocation.<OnProofFinished>getArgument(0).onFinished(historyStore, construction, new TreeMap<>());
                    return null;
                })
                .given(historyService)
                .onFinishedConstruction(any());
        given(historyService.historyProofVerificationKey()).willReturn(Bytes.EMPTY);
        given(state.getReadableStates(RosterService.NAME)).willReturn(mock(ReadableStates.class));

        final var creatorId = NodeId.of(0L);
        given(round.iterator()).willAnswer(_ -> List.of(event).iterator());
        given(event.getConsensusTimestamp()).willReturn(NOW);
        given(event.allParentsIterator()).willReturn(emptyIterator());
        given(event.getCreatorId()).willReturn(creatorId);
        final var lastUsedConsTime = new AtomicReference<>(beforeEvents);
        // Stands in for handling the round's transactions, which advances the last-used consensus time
        given(event.consensusTransactionIterator()).willAnswer(invocation -> {
            lastUsedConsTime.set(afterEvents);
            return emptyIterator();
        });
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(blockHashSigner.isReady()).willReturn(true);
        given(blockStreamManager.lastUsedConsensusTime()).willAnswer(invocation -> lastUsedConsTime.get());
        given(blockStreamManager.lastIntervalProcessTime()).willReturn(NOW);

        givenSubjectWith(
                BLOCKS,
                BlockStreamWriterMode.FILE,
                emptyList(),
                Map.of("tss.hintsEnabled", "true", "tss.historyEnabled", "true"));

        // Both dispatches under test run through a real SystemTransactions, so each transaction is assigned the
        // consensus time production would give it; the dispatch is then aborted, since carrying one out needs the
        // entire handle stack
        given(appContext.idFactory()).willReturn(entityIdFactory);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(AccountID.DEFAULT);
        given(networkInfo.addressBook()).willReturn(List.of(creatorInfo));
        final var realSystemTransactions = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);
        final var assignedConsTimes = ArgumentCaptor.forClass(Instant.class);
        given(parentTxnFactory.createSystemTxn(any(), any(), assignedConsTimes.capture(), any(), any(), any()))
                .willThrow(new IllegalStateException("Aborting dispatch after its consensus time is assigned"));
        // Mirrors NodeFeeManager.distributeFees(), which forwards its consensus time to dispatchNodePayments()
        given(nodeFeeManager.distributeFees(any(), any(), any())).willAnswer(invocation -> {
            realSystemTransactions.dispatchNodePayments(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    TransferList.newBuilder()
                            .accountAmounts(AccountAmount.newBuilder()
                                    .accountID(AccountID.DEFAULT)
                                    .amount(1L)
                                    .build())
                            .build());
            return true;
        });
        willAnswer(invocation -> {
                    realSystemTransactions.externalizeLedgerId(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5));
                    return null;
                })
                .given(systemTransactions)
                .externalizeLedgerId(any(), any(), any(), any(), any(), any());

        subject.handleRound(state, round, txns -> {});

        final var consTimes = assignedConsTimes.getAllValues();
        assertEquals(2, consTimes.size(), "Expected the node fee payment and the ledger id publication to dispatch");
        final var nodeFeeConsTime = consTimes.getFirst();
        final var ledgerIdConsTime = consTimes.getLast();
        assertNotEquals(
                nodeFeeConsTime,
                ledgerIdConsTime,
                "The node fee payment and the ledger id publication were assigned the same consensus time, "
                        + nodeFeeConsTime);
        assertTrue(
                ledgerIdConsTime.isAfter(afterEvents),
                "The ledger id publication was assigned " + ledgerIdConsTime + ", which precedes the last transaction "
                        + "handled in the round at " + afterEvents);
    }
}
