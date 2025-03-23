// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConsistencyTestingToolStateTest {

    private static ConsistencyTestingToolState state;
    private static ConsistencyTestingToolConsensusStateEventHandler stateLifecycle;
    private Random random;
    private Platform platform;
    private PlatformContext platformContext;
    private Round round;
    private ConsensusEvent event;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedTransactions;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private Transaction consensusTransaction;
    private StateSignatureTransaction stateSignatureTransaction;
    private InitTrigger initTrigger;
    private SemanticVersion softwareVersion;
    private Configuration configuration;
    private ConsistencyTestingToolConfig consistencyTestingToolConfig;
    private StateCommonConfig stateCommonConfig;

    @BeforeAll
    static void initState() {
        state = new ConsistencyTestingToolState();
        stateLifecycle = new ConsistencyTestingToolConsensusStateEventHandler(DEFAULT_PLATFORM_STATE_FACADE);
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initStates(state);
    }

    @BeforeEach
    void setUp() {
        platform = mock(Platform.class);
        initTrigger = InitTrigger.GENESIS;
        softwareVersion = new BasicSoftwareVersion(1).getPbjSemanticVersion();
        platformContext = mock(PlatformContext.class);
        configuration = mock(Configuration.class);
        consistencyTestingToolConfig = mock(ConsistencyTestingToolConfig.class);
        stateCommonConfig = mock(StateCommonConfig.class);

        when(platform.getSelfId()).thenReturn(NodeId.of(1L));
        when(platform.getContext()).thenReturn(platformContext);
        when(platformContext.getConfiguration()).thenReturn(configuration);
        when(configuration.getConfigData(ConsistencyTestingToolConfig.class)).thenReturn(consistencyTestingToolConfig);
        when(configuration.getConfigData(StateCommonConfig.class)).thenReturn(stateCommonConfig);
        when(consistencyTestingToolConfig.freezeAfterGenesis()).thenReturn(Duration.ZERO);
        when(stateCommonConfig.savedStateDirectory()).thenReturn(Path.of("consistency-test"));
        when(consistencyTestingToolConfig.logfileDirectory()).thenReturn("consistency-test");

        stateLifecycle.onStateInitialized(state, platform, initTrigger, softwareVersion);

        random = new Random();
        round = mock(Round.class);
        event = mock(ConsensusEvent.class);

        consumedTransactions = new ArrayList<>();
        consumer = systemTransaction -> consumedTransactions.add(systemTransaction);
        consensusTransaction = mock(TransactionWrapper.class);

        final byte[] signature = new byte[384];
        random.nextBytes(signature);
        final byte[] hash = new byte[48];
        random.nextBytes(hash);
        stateSignatureTransaction = StateSignatureTransaction.newBuilder()
                .signature(Bytes.wrap(signature))
                .hash(Bytes.wrap(hash))
                .round(round.getRoundNum())
                .build();
    }

    @Test
    void handleConsensusRoundWithApplicationTransaction() {
        final var bytes = Bytes.wrap(new byte[] {1, 1, 1, 1, 1, 1, 1, 1});
        when(consensusTransaction.getApplicationTransaction()).thenReturn(bytes);

        doAnswer(invocation -> {
                    BiConsumer<ConsensusEvent, Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(event, consensusTransaction);
                    return null;
                })
                .when(round)
                .forEachEventTransaction(any());

        stateLifecycle.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        doAnswer(invocation -> {
                    BiConsumer<ConsensusEvent, Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(event, consensusTransaction);
                    return null;
                })
                .when(round)
                .forEachEventTransaction(any());

        stateLifecycle.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void handleConsensusRoundWithMultipleSystemTransactions() {
        // Given
        final var secondConsensusTransaction = mock(TransactionWrapper.class);
        final var thirdConsensusTransaction = mock(TransactionWrapper.class);
        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        doAnswer(invocation -> {
                    BiConsumer<ConsensusEvent, Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(event, consensusTransaction);
                    consumer.accept(event, secondConsensusTransaction);
                    consumer.accept(event, thirdConsensusTransaction);
                    return null;
                })
                .when(round)
                .forEachEventTransaction(any());

        // When
        stateLifecycle.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithMultipleSystemTransactions() {
        final var secondConsensusTransaction = mock(TransactionWrapper.class);
        final var thirdConsensusTransaction = mock(TransactionWrapper.class);
        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        doAnswer(invocation -> {
                    Consumer<Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(consensusTransaction);
                    consumer.accept(secondConsensusTransaction);
                    consumer.accept(thirdConsensusTransaction);
                    return null;
                })
                .when(event)
                .forEachTransaction(any());

        stateLifecycle.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithSystemTransaction() {
        final var emptyStateSignatureBytes = StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(emptyStateSignatureBytes);

        doAnswer(invocation -> {
                    Consumer<Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(consensusTransaction);
                    return null;
                })
                .when(event)
                .forEachTransaction(any());

        stateLifecycle.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void preHandleEventWithApplicationTransaction() {
        final var bytes = Bytes.wrap(new byte[] {1, 1, 1, 1, 1, 1, 1, 1});
        when(consensusTransaction.getApplicationTransaction()).thenReturn(bytes);

        doAnswer(invocation -> {
                    Consumer<Transaction> consumer = invocation.getArgument(0);
                    consumer.accept(consensusTransaction);
                    return null;
                })
                .when(event)
                .forEachTransaction(any());

        stateLifecycle.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void onSealDefaultsToTrue() {
        final boolean result = stateLifecycle.onSealConsensusRound(round, state);

        assertThat(result).isTrue();
    }
}
