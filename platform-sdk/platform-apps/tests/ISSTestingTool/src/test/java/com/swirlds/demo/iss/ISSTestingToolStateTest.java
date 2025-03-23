// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.singleton.StringLeaf;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ISSTestingToolStateTest {

    private static final int RUNNING_SUM_INDEX = 3;
    private ISSTestingToolMain main;
    private ISSTestingToolState state;
    private ISSTestingToolConsensusStateEventHandler consensusStateEventHandler;
    private Round round;
    private ConsensusEvent event;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedTransactions;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private Transaction transaction;
    private StateSignatureTransaction stateSignatureTransaction;

    @BeforeEach
    void setUp() {
        state = new ISSTestingToolState();
        consensusStateEventHandler = new ISSTestingToolConsensusStateEventHandler();
        main = mock(ISSTestingToolMain.class);
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

        assertThat(Long.parseLong(((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).getLabel()))
                .isPositive();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        // Given
        givenRoundAndEvent();

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(main.encodeSystemTransaction(stateSignatureTransaction)).thenReturn(stateSignatureTransactionBytes);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).isNull();
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
        when(main.encodeSystemTransaction(stateSignatureTransaction)).thenReturn(stateSignatureTransactionBytes);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).isNull();
        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void handleConsensusRoundWithEmptyTransaction() {
        // Given
        givenRoundAndEvent();

        final var emptyStateSignatureTransaction = StateSignatureTransaction.DEFAULT;
        final var emptyStateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(emptyStateSignatureTransaction);
        when(main.encodeSystemTransaction(emptyStateSignatureTransaction))
                .thenReturn(emptyStateSignatureTransactionBytes);
        when(transaction.getApplicationTransaction()).thenReturn(emptyStateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat(Long.parseLong(((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).getLabel()))
                .isZero();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithNullTransaction() {
        // Given
        givenRoundAndEvent();

        final var emptyStateSignatureTransaction = StateSignatureTransaction.DEFAULT;
        final var emptyStateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(emptyStateSignatureTransaction);
        when(main.encodeSystemTransaction(null))
                .thenReturn(StateSignatureTransaction.PROTOBUF.toBytes(emptyStateSignatureTransaction));
        when(transaction.getApplicationTransaction()).thenReturn(emptyStateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat(Long.parseLong(((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).getLabel()))
                .isZero();
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void preHandleEventWithMultipleSystemTransaction() {
        // Given
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        when(eventCore.creatorNodeId()).thenReturn(1L);
        when(eventCore.parents()).thenReturn(Collections.emptyList());
        final var consensusTransaction = mock(TransactionWrapper.class);
        final var secondConsensusTransaction = mock(TransactionWrapper.class);
        final var thirdConsensusTransaction = mock(TransactionWrapper.class);

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);

        when(consensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);
        when(gossipEvent.transactions()).thenReturn(List.of(transactionBytes, transactionBytes, transactionBytes));
        event = new PlatformEvent(gossipEvent);
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithSystemTransaction() {
        // Given
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        when(eventCore.creatorNodeId()).thenReturn(1L);
        when(eventCore.parents()).thenReturn(Collections.emptyList());
        final var consensusTransaction = mock(TransactionWrapper.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);
        when(gossipEvent.transactions()).thenReturn(List.of(transactionBytes));

        event = new PlatformEvent(gossipEvent);

        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.getApplicationTransaction()).thenReturn(transactionBytes);

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void preHandleEventWithEmptyTransaction() {
        // Given
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        when(eventCore.creatorNodeId()).thenReturn(1L);
        when(eventCore.parents()).thenReturn(Collections.emptyList());
        final var consensusTransaction = mock(TransactionWrapper.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(StateSignatureTransaction.DEFAULT);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);

        event = new PlatformEvent(gossipEvent);
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.getApplicationTransaction()).thenReturn(transactionBytes);

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
