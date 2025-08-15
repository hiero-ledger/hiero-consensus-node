// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Collections.emptyIterator;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
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
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.throttle.CongestionMetrics;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
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
    private BlockRecordManager blockRecordManager;

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
    private SemanticVersion version;

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
    private State state;

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
    private PlatformStateFacade platformStateFacade;

    @Mock
    private BlockBufferService blockBufferService;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private EventCore eventCore;

    @Mock
    private ReadableSingletonState<Object> platformStateReadableSingletonState;

    private HandleWorkflow subject;

    @Test
    void onlySkipsEventWithMissingCreator() {
        final var presentCreatorId = NodeId.of(1L);
        final var missingCreatorId = NodeId.of(2L);
        final var eventFromPresentCreator = mock(ConsensusEvent.class);
        final var eventFromMissingCreator = mock(ConsensusEvent.class);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);
        given(platformStateReadableSingletonState.get())
                .willReturn(PlatformState.newBuilder().latestFreezeRound(0L).build());
        given(eventFromMissingCreator.getEventCore()).willReturn(eventCore);
        given(round.iterator())
                .willReturn(List.of(eventFromMissingCreator, eventFromPresentCreator)
                        .iterator());
        given(eventFromPresentCreator.getCreatorId()).willReturn(presentCreatorId);
        given(eventFromMissingCreator.getCreatorId()).willReturn(missingCreatorId);
        given(networkInfo.nodeInfo(presentCreatorId.id())).willReturn(mock(NodeInfo.class));
        given(networkInfo.nodeInfo(missingCreatorId.id())).willReturn(null);
        given(eventFromPresentCreator.consensusTransactionIterator()).willReturn(emptyIterator());
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(12345L));
        given(blockRecordManager.consTimeOfLastHandledTxn()).willReturn(NOW);

        givenSubjectWith(RECORDS, BlockStreamWriterMode.FILE, emptyList());

        subject.handleRound(state, round, txns -> {});

        verify(eventFromPresentCreator).consensusTransactionIterator();
        verify(recordCache).resetRoundReceipts();
        verify(recordCache)
                .commitReceipts(any(), any(), same(immediateStateChangeListener), same(blockStreamManager), any());
    }

    @Test
    void writesEachMigrationStateChangeWithBlockTimestamp() {
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        given(round.iterator()).willReturn(List.of(event).iterator());
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
    void writeEventHeaderWithNoParentEvents() {
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        // Setup event with no parents
        given(event.getHash()).willReturn(CryptoRandomUtils.randomHash());
        given(event.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);

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
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        // Create event hash and parent hash
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentHash = CryptoRandomUtils.randomHash();

        // Setup parent in current block
        given(blockStreamManager.getEventIndex(parentHash)).willReturn(Optional.of(5)); // Parent is at index 5

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
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        // Create event hash and parent hash
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentHash = CryptoRandomUtils.randomHash();

        // Setup parent not in current block
        given(blockStreamManager.getEventIndex(parentHash)).willReturn(Optional.empty());

        // Setup event with one parent
        EventDescriptor parentDescriptor = EventDescriptor.newBuilder().build();
        EventDescriptorWrapper parent = mock(EventDescriptorWrapper.class);
        given(parent.hash()).willReturn(parentHash);
        given(parent.eventDescriptor()).willReturn(parentDescriptor);

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
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        // Create event hash and parent hashes
        Hash eventHash = CryptoRandomUtils.randomHash();
        Hash parentInBlockHash = CryptoRandomUtils.randomHash();
        Hash parentNotInBlockHash = CryptoRandomUtils.randomHash();

        // Setup parents - one in block, one not in block
        given(blockStreamManager.getEventIndex(parentInBlockHash)).willReturn(Optional.of(3));
        given(blockStreamManager.getEventIndex(parentNotInBlockHash)).willReturn(Optional.empty());

        // Setup descriptors for parents
        EventDescriptor notInBlockDescriptor = EventDescriptor.newBuilder().build();

        // Setup parent wrappers
        EventDescriptorWrapper parentInBlock = mock(EventDescriptorWrapper.class);
        given(parentInBlock.hash()).willReturn(parentInBlockHash);

        EventDescriptorWrapper parentNotInBlock = mock(EventDescriptorWrapper.class);
        given(parentNotInBlock.hash()).willReturn(parentNotInBlockHash);
        given(parentNotInBlock.eventDescriptor()).willReturn(notInBlockDescriptor);

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
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "" + mode)
                .withValue("blockStream.writerMode", "" + streamWriterMode)
                .withValue("tss.hintsEnabled", "false")
                .withValue("tss.historyEnabled", "false")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        given(round.getConsensusTimestamp()).willReturn(NOW);
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
                immediateStateChangeListener,
                scheduleService,
                hintsService,
                historyService,
                congestionMetrics,
                () -> PlatformStatus.ACTIVE,
                blockHashSigner,
                null,
                nodeRewardManager,
                platformStateFacade,
                blockBufferService);
    }

    @Test
    void startRoundShouldCallEnsureNewBlocksPermitted() {
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(any())).willReturn(platformStateReadableSingletonState);

        // Mock the round iterator and event
        final NodeId creatorId = NodeId.of(0);
        final Hash eventHash = CryptoRandomUtils.randomHash();
        given(event.getHash()).willReturn(eventHash);
        given(event.getCreatorId()).willReturn(creatorId);
        given(event.getEventCore()).willReturn(EventCore.DEFAULT);
        given(event.allParentsIterator())
                .willReturn(List.<EventDescriptorWrapper>of().iterator());
        given(networkInfo.nodeInfo(creatorId.id())).willReturn(mock(NodeInfo.class));
        given(event.consensusTransactionIterator()).willReturn(emptyIterator());
        given(round.iterator()).willAnswer(invocationOnMock -> List.of(event).iterator());

        // Create subject with streamToBlockNodes enabled
        givenSubjectWith(BOTH, BlockStreamWriterMode.FILE_AND_GRPC, emptyList());

        subject.handleRound(state, round, txn -> {});

        verify(blockBufferService).ensureNewBlocksPermitted();
    }
}
