// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static com.swirlds.demo.iss.ISSTestingToolMain.CONFIGURATION;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.ISS_SERVICE_NAME;
import static com.swirlds.demo.iss.V0680ISSTestingToolSchema.RUNNING_SUM_STATE_ID;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ISSTestingToolStateTest {

    private ISSTestingToolState state;
    private ISSTestingToolConsensusStateEventHandler consensusStateEventHandler;
    private Round round;
    private ConsensusEvent event;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedTransactions;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private Transaction transaction;
    private StateSignatureTransaction stateSignatureTransaction;
    private Bytes signatureTransactionBytes;

    @BeforeEach
    void setUp() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(
                CONFIGURATION, merkleDbConfig.initialCapacity(), merkleDbConfig.hashesRamToDiskThreshold());
        final VirtualMap virtualMap = new VirtualMap("ISSTestingToolStateTest", dsBuilder, CONFIGURATION);
        state = new ISSTestingToolState(virtualMap);

        final var schema = new V0680ISSTestingToolSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    state.initializeState(new StateMetadata<>(ISS_SERVICE_NAME, def));
                });

        consensusStateEventHandler = new ISSTestingToolConsensusStateEventHandler();
        final var random = new Random();
        round = mock(Round.class);
        event = mock(ConsensusEvent.class);

        consumedTransactions = new ArrayList<>();
        consumer = systemTransaction -> consumedTransactions.add(systemTransaction);
        transaction = mock(TransactionWrapper.class);

        final byte[] signature = new byte[384];
        random.nextBytes(signature);
        final byte[] hash = new byte[48];
        random.nextBytes(hash);
        stateSignatureTransaction = StateSignatureTransaction.newBuilder()
                .signature(Bytes.wrap(signature))
                .hash(Bytes.wrap(hash))
                .round(round.getRoundNum())
                .build();
        signatureTransactionBytes = StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
    }

    @AfterEach
    void tearDown() {
        state.release();
    }

    @Test
    void handleConsensusRoundWithApplicationTransaction() {
        // Given
        givenRoundAndEvent();

        final var bytes = Bytes.wrap(new byte[] {1, 1, 1, 1});
        when(transaction.getApplicationTransaction()).thenReturn(bytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        final ReadableSingletonState<ProtoLong> runningSumState =
                state.getReadableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        final ProtoLong runningSumProto = runningSumState.get();
        assertThat(runningSumProto.value()).isPositive();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        // Given
        givenRoundAndEvent();

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        final ReadableSingletonState<ProtoLong> runningSumState =
                state.getReadableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        assertThat(runningSumState.get()).isNull();
        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void handleConsensusRoundWithMultipleSystemTransaction() {
        // Given
        final var secondConsensusTransaction = mock(TransactionWrapper.class);
        final var thirdConsensusTransaction = mock(TransactionWrapper.class);
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(List.of(
                                (ConsensusTransaction) transaction,
                                secondConsensusTransaction,
                                thirdConsensusTransaction)
                        .iterator());

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        final ReadableSingletonState<ProtoLong> runningSumState =
                state.getReadableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        assertThat(runningSumState.get()).isNull();
        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void handleConsensusRoundWithEmptyTransaction() {
        // Given
        givenRoundAndEvent();

        final var emptyStateSignatureTransaction = StateSignatureTransaction.DEFAULT;
        final var emptyStateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(emptyStateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(emptyStateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        final ReadableSingletonState<ProtoLong> runningSumState =
                state.getReadableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        final ProtoLong runningSumProto = runningSumState.get();
        assertThat(runningSumProto.value()).isZero();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithNullTransaction() {
        // Given
        givenRoundAndEvent();

        final var emptyStateSignatureTransaction = StateSignatureTransaction.DEFAULT;
        final var emptyStateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(emptyStateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(emptyStateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        final ReadableSingletonState<ProtoLong> runningSumState =
                state.getReadableStates(ISS_SERVICE_NAME).getSingleton(RUNNING_SUM_STATE_ID);
        final ProtoLong runningSumProto = runningSumState.get();
        assertThat(runningSumProto.value()).isZero();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void preHandleEventWithMultipleSystemTransaction() {
        // Given
        final PlatformEvent event = new TestingEventBuilder(Randotron.create())
                .setTransactionBytes(Collections.nCopies(3, signatureTransactionBytes))
                .build();
        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithSystemTransaction() {
        // Given
        final PlatformEvent event = new TestingEventBuilder(Randotron.create())
                .setTransactionBytes(List.of(signatureTransactionBytes))
                .build();

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void preHandleEventWithEmptyTransaction() {
        // Given
        final PlatformEvent event = new TestingEventBuilder(Randotron.create())
                .setAppTransactionCount(0)
                .setSystemTransactionCount(0)
                .build();

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void onSealDefaultsToTrue() {
        // Given (empty)

        // When
        final boolean result = consensusStateEventHandler.onSealConsensusRound(round, state);

        // Then
        assertThat(result).isTrue();
    }

    private void givenRoundAndEvent() {
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(Collections.singletonList((ConsensusTransaction) transaction)
                        .iterator());
    }
}
