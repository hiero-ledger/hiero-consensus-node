// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.SignatureException;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class MigrationTestingToolStateTest {
    private MigrationTestingToolState state;
    private MigrationTestToolConsensusStateEventHandler consensusStateEventHandler;
    private Random random;
    private Round round;
    private ConsensusEvent event;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedTransactions;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private Transaction transaction;
    private StateSignatureTransaction stateSignatureTransaction;

    @BeforeEach
    void setUp() {
        state = new MigrationTestingToolState();
        consensusStateEventHandler = new MigrationTestToolConsensusStateEventHandler();
        random = new Random();
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
    void handleConsensusRoundWithApplicationTransaction() throws SignatureException {
        givenRoundAndEvent();
        final TransactionGenerator generator = new TransactionGenerator(5);
        final var bytes = Bytes.wrap(generator.generateTransaction());
        final var tr = TransactionUtils.parseTransaction(bytes);
        when(transaction.getApplicationTransaction()).thenReturn(bytes);

        try (MockedStatic<TransactionUtils> utilities =
                Mockito.mockStatic(TransactionUtils.class, Mockito.CALLS_REAL_METHODS)) {
            MigrationTestingToolTransaction migrationTestingToolTransaction = Mockito.spy(tr);
            utilities.when(() -> TransactionUtils.parseTransaction(any())).thenReturn(migrationTestingToolTransaction);
            Mockito.doNothing().when(migrationTestingToolTransaction).applyTo(state);
            consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);
        }

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        givenRoundAndEvent();
        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void handleConsensusRoundWithMultipleSystemTransactions() {
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

        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithMultipleSystemTransactions() {
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
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

        consensusStateEventHandler.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithSystemTransaction() {
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
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

        consensusStateEventHandler.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void onSealDefaultsToTrue() {
        final boolean result = consensusStateEventHandler.onSealConsensusRound(round, state);

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
