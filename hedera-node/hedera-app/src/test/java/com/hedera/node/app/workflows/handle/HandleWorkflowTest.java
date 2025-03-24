// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Collections.emptyIterator;
import static java.util.Collections.emptyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.KVStateChangeListener;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.throttle.CongestionMetrics;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleWorkflowTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Timestamp BLOCK_TIME = new Timestamp(1_234_567L, 890);

    @Mock
    private HintsService hintsService;

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
    private KVStateChangeListener kvStateChangeListener;

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

    private HandleWorkflow subject;

    private Function<SemanticVersion, SoftwareVersion> softwareVersionFactory;

    @BeforeEach
    void setUp() {
        softwareVersionFactory = ServicesSoftwareVersion::new;
    }

    @Test
    void onlySkipsEventWithMissingCreator() {
        final var presentCreatorId = NodeId.of(1L);
        final var missingCreatorId = NodeId.of(2L);
        final var eventFromPresentCreator = mock(ConsensusEvent.class);
        final var eventFromMissingCreator = mock(ConsensusEvent.class);
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

        givenSubjectWith(RECORDS, emptyList());

        subject.handleRound(state, round, txns -> {});

        verify(eventFromPresentCreator).consensusTransactionIterator();
        verify(recordCache).resetRoundReceipts();
        verify(recordCache).commitRoundReceipts(any(), any());
    }

    @Test
    void writesEachMigrationStateChangeWithBlockTimestamp() {
        given(round.iterator()).willReturn(List.of(event).iterator());
        given(event.getConsensusTimestamp()).willReturn(NOW);
        given(systemTransactions.startupWorkConsTimeFor(any())).willReturn(NOW);
        final var firstBuilder = StateChanges.newBuilder().stateChanges(List.of(StateChange.DEFAULT));
        final var secondBuilder =
                StateChanges.newBuilder().stateChanges(List.of(StateChange.DEFAULT, StateChange.DEFAULT));
        final var builders = List.of(firstBuilder, secondBuilder);
        givenSubjectWith(BOTH, builders);

        subject.handleRound(state, round, txns -> {});

        builders.forEach(builder -> verify(blockStreamManager)
                .writeItem(BlockItem.newBuilder()
                        .stateChanges(builder.consensusTimestamp(BLOCK_TIME).build())
                        .build()));
    }

    private void givenSubjectWith(
            @NonNull final StreamMode mode, @NonNull final List<StateChanges.Builder> migrationStateChanges) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "" + mode)
                .withValue("tss.hintsEnabled", "false")
                .withValue("tss.historyEnabled", "false")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
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
                version,
                initTrigger,
                hollowAccountCompletions,
                systemTransactions,
                stakeInfoHelper,
                recordCache,
                exchangeRateManager,
                stakePeriodManager,
                migrationStateChanges,
                parentTxnFactory,
                kvStateChangeListener,
                boundaryStateChangeListener,
                scheduleService,
                hintsService,
                historyService,
                congestionMetrics,
                softwareVersionFactory,
                () -> PlatformStatus.ACTIVE,
                null);
    }
}
