/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.demo.migration;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.hapi.platform.event.EventTransaction.TransactionOneOfType;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.events.ConsensusEvent;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.platform.system.transaction.TransactionWrapper;
import java.security.SignatureException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class MigrationTestingToolStateTest {
    private MigrationTestingToolState state;
    private MigrationTestToolStateLifecycles stateLifecycles;
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
        stateLifecycles = new MigrationTestToolStateLifecycles();
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
            stateLifecycles.onHandleConsensusRound(round, state, consumer);
        }

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        givenRoundAndEvent();
        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        stateLifecycles.onHandleConsensusRound(round, state, consumer);

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

        stateLifecycles.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void handleConsensusRoundWithDeprecatedSystemTransaction() {
        givenRoundAndEvent();
        when(transaction.getApplicationTransaction()).thenReturn(Bytes.EMPTY);
        when(transaction.isSystem()).thenReturn(true);

        stateLifecycles.onHandleConsensusRound(round, state, consumer);

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void preHandleEventWithMultipleSystemTransactions() {
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        final var eventTransaction = mock(EventTransaction.class);
        final var secondEventTransaction = mock(EventTransaction.class);
        final var thirdEventTransaction = mock(EventTransaction.class);

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);

        final var systemTransactionWithType =
                new OneOf<>(TransactionOneOfType.APPLICATION_TRANSACTION, transactionBytes);

        when(eventTransaction.transaction()).thenReturn(systemTransactionWithType);
        when(secondEventTransaction.transaction()).thenReturn(systemTransactionWithType);
        when(thirdEventTransaction.transaction()).thenReturn(systemTransactionWithType);
        when(gossipEvent.eventTransaction())
                .thenReturn(List.of(eventTransaction, secondEventTransaction, thirdEventTransaction));
        event = new PlatformEvent(gossipEvent);

        stateLifecycles.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void preHandleEventWithSystemTransaction() {
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        final var eventTransaction = mock(EventTransaction.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);
        when(gossipEvent.eventTransaction()).thenReturn(List.of(eventTransaction));

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);
        final var systemTransactionWithType =
                new OneOf<>(TransactionOneOfType.APPLICATION_TRANSACTION, transactionBytes);
        when(eventTransaction.transaction()).thenReturn(systemTransactionWithType);
        event = new PlatformEvent(gossipEvent);

        stateLifecycles.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void preHandleEventWithDeprecatedSystemTransaction() {
        event = mock(PlatformEvent.class);

        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.isSystem()).thenReturn(true);

        stateLifecycles.onPreHandle(event, state, consumer);

        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void onSealDefaultsToTrue() {
        final boolean result = stateLifecycles.onSealConsensusRound(round, state);

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
