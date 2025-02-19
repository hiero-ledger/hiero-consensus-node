/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.demo.iss;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.swirlds.state.merkle.singleton.StringLeaf;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ISSTestingToolStateTest {

    private static final int RUNNING_SUM_INDEX = 3;
    private ISSTestingToolMain main;
    private ISSTestingToolState state;
    private ISSTestingToolStateLifecycles stateLifecycles;
    private Round round;
    private ConsensusEvent event;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedTransactions;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private Transaction transaction;
    private StateSignatureTransaction stateSignatureTransaction;

    @BeforeEach
    void setUp() {
        state = new ISSTestingToolState();
        stateLifecycles = new ISSTestingToolStateLifecycles();
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
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

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
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

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
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).isNull();
        assertThat(consumedTransactions).hasSize(3);
    }

    @Test
    void handleConsensusRoundWithDeprecatedSystemTransaction() {
        // Given
        givenRoundAndEvent();

        when(transaction.getApplicationTransaction()).thenReturn(Bytes.EMPTY);
        when(transaction.isSystem()).thenReturn(true);

        // When
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

        // Then
        verify(round, times(1)).iterator();
        verify(event, times(2)).getConsensusTimestamp();
        verify(event, times(1)).consensusTransactionIterator();

        assertThat((StringLeaf) state.getChild(RUNNING_SUM_INDEX)).isNull();
        assertThat(consumedTransactions).isEmpty();
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
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

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
        stateLifecycles.onHandleConsensusRound(round, state, consumer);

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
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.getApplicationTransaction()).thenReturn(transactionBytes);
        when(secondEventTransaction.applicationTransaction()).thenReturn(transactionBytes);
        when(thirdEventTransaction.applicationTransaction()).thenReturn(transactionBytes);

        // When
        stateLifecycles.onPreHandle(event, state, consumer);

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

        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.getApplicationTransaction()).thenReturn(transactionBytes);

        // When
        stateLifecycles.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).hasSize(1);
    }

    @Test
    void preHandleEventWithDeprecatedSystemTransaction() {
        // Given
        event = mock(PlatformEvent.class);

        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.isSystem()).thenReturn(true);

        // When
        stateLifecycles.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void preHandleEventWithEmptyTransaction() {
        // Given
        final var gossipEvent = mock(GossipEvent.class);
        final var eventCore = mock(EventCore.class);
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        when(eventCore.creatorNodeId()).thenReturn(1L);
        when(eventCore.parents()).thenReturn(Collections.emptyList());
        final var eventTransaction = mock(EventTransaction.class);
        when(gossipEvent.eventCore()).thenReturn(eventCore);
        when(gossipEvent.eventTransaction()).thenReturn(List.of(eventTransaction));

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(StateSignatureTransaction.DEFAULT);
        final var transactionProto = com.hedera.hapi.node.base.Transaction.newBuilder()
                .bodyBytes(stateSignatureTransactionBytes)
                .build();
        final var transactionBytes = com.hedera.hapi.node.base.Transaction.PROTOBUF.toBytes(transactionProto);
        final var systemTransactionWithType =
                new OneOf<>(TransactionOneOfType.APPLICATION_TRANSACTION, transactionBytes);
        when(eventTransaction.transaction()).thenReturn(systemTransactionWithType);

        event = new PlatformEvent(gossipEvent);
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
        when(transaction.getApplicationTransaction()).thenReturn(transactionBytes);

        // When
        stateLifecycles.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedTransactions).isEmpty();
    }

    @Test
    void onSealDefaultsToTrue() {
        // Given (empty)

        // When
        final boolean result = stateLifecycles.onSealConsensusRound(round, state);

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
