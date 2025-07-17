// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.demo.stats.signing.algorithms.X25519SigningAlgorithm;
import java.security.SignatureException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class StatsSigningTestingToolStateTest {

    private static final int transactionSize = 100;
    private Random random;
    private StatsSigningTestingToolState state;
    private StatsSigningTestingToolConsensusStateEventHandler consensusStateEventHandler;
    private StatsSigningTestingToolMain main;
    private Round round;
    private PlatformEvent event;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedSystemTransactions;
    private ConsensusTransaction consensusTransaction;
    private StateSignatureTransaction stateSignatureTransaction;

    @BeforeEach
    void setUp() {
        final SttTransactionPool transactionPool = mock(SttTransactionPool.class);
        final Supplier<SttTransactionPool> transactionPoolSupplier = mock(Supplier.class);
        state = new StatsSigningTestingToolState();
        consensusStateEventHandler = new StatsSigningTestingToolConsensusStateEventHandler(transactionPoolSupplier);
        main = new StatsSigningTestingToolMain();
        random = new Random();
        event = mock(PlatformEvent.class);

        final var eventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(10)
                .setAncientThreshold(5)
                .setExpiredThreshold(20)
                .build();
        final var roster = new Roster(Collections.EMPTY_LIST);
        when(event.transactionIterator()).thenReturn(Collections.emptyIterator());
        round = new ConsensusRound(
                roster, List.of(event), eventWindow, Mockito.mock(ConsensusSnapshot.class), false, Instant.now());

        consumedSystemTransactions = new ArrayList<>();
        consumer = systemTransaction -> consumedSystemTransactions.add(systemTransaction);
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

        when(transactionPoolSupplier.get()).thenReturn(transactionPool);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void handleConsensusRoundWithApplicationTransaction(final boolean signedTransaction) throws SignatureException {
        // Given
        givenRoundAndEvent();

        final var transactionBytes =
                signedTransaction ? getSignedApplicationTransaction() : getUnsignedApplicationTransaction();

        when(consensusTransaction.getApplicationTransaction()).thenReturn(transactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isZero();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        // Given
        givenRoundAndEvent();

        final var stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        when(consensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isEqualTo(1);
    }

    @Test
    void handleConsensusRoundWithMultipleSystemTransaction() {
        // Given
        final var secondConsensusTransaction = mock(TransactionWrapper.class);
        final var thirdConsensusTransaction = mock(TransactionWrapper.class);
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(List.of(consensusTransaction, secondConsensusTransaction, thirdConsensusTransaction)
                        .iterator());

        final var stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);

        when(consensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preHandleConsensusRoundWithApplicationTransaction(final boolean signedTransaction) throws SignatureException {
        // Given
        givenRoundAndEvent();

        final var bytes = signedTransaction ? getSignedApplicationTransaction() : getUnsignedApplicationTransaction();

        final var eventCore = mock(EventCore.class);
        final var gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(bytes)
                .build();

        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        event = new PlatformEvent(gossipEvent);

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isZero();
    }

    @Test
    void preHandleConsensusRoundWithSystemTransaction() {
        // Given
        givenRoundAndEvent();

        final var stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        final var eventCore = mock(EventCore.class);
        final var gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(stateSignatureTransactionBytes)
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        event = new PlatformEvent(gossipEvent);

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isEqualTo(1);
    }

    @Test
    void preHandleConsensusRoundWithMultipleSystemTransaction() {
        // Given
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());

        final var stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);

        final var eventCore = mock(EventCore.class);
        final var gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(List.of(
                        stateSignatureTransactionBytes, stateSignatureTransactionBytes, stateSignatureTransactionBytes))
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        event = new PlatformEvent(gossipEvent);

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isEqualTo(3);
    }

    @Test
    void preHandleConsensusRoundWithDeprecatedSystemTransaction() {
        // Given
        givenRoundAndEvent();

        final var stateSignatureTransactionBytes =
                StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction);
        final var eventCore = mock(EventCore.class);
        final var gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(stateSignatureTransactionBytes)
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        event = new PlatformEvent(gossipEvent);

        // When
        consensusStateEventHandler.onPreHandle(event, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isZero();
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
        when(event.getCreatorId()).thenReturn(new NodeId());
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(Collections.singletonList(consensusTransaction).iterator());
    }

    private Bytes getSignedApplicationTransaction() throws SignatureException {
        final byte[] data = new byte[transactionSize];
        random.nextBytes(data);

        final var alg = new X25519SigningAlgorithm();
        alg.tryAcquirePrimitives();
        final var exSig = alg.signEx(data, 0, data.length);
        final var sig = exSig.getSignature();
        final var transactionId = 80_000L;
        return Bytes.wrap(TransactionCodec.encode(alg, transactionId, sig, data));
    }

    private Bytes getUnsignedApplicationTransaction() {
        final byte[] data = new byte[transactionSize];
        random.nextBytes(data);

        final var transactionId = 80_000L;
        return Bytes.wrap(TransactionCodec.encode(null, transactionId, null, data));
    }
}
