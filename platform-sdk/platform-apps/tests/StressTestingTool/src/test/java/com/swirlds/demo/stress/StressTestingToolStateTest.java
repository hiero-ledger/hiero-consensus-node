// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stress;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.internal.MerkleCryptoEngine;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StressTestingToolStateTest {

    private static final byte[] EMPTY_ARRAY = new byte[] {};
    private StressTestingToolState state;
    private StressTestingToolMain main;
    private StressTestingToolConsensusStateEventHandler consensusStateEventHandler;
    private PlatformStateModifier platformStateModifier;
    private Round round;
    private ConsensusEvent event;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedSystemTransactions;
    private Transaction transaction;
    private StateSignatureTransaction stateSignatureTransaction;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException, KeyStoreException, KeyGeneratingException, NoSuchProviderException {
        state = new StressTestingToolState();
        consensusStateEventHandler = new StressTestingToolConsensusStateEventHandler();
        main = new StressTestingToolMain();
        platformStateModifier = mock(PlatformStateModifier.class);
        event = mock(PlatformEvent.class);

        when(event.transactionIterator()).thenReturn(Collections.emptyIterator());
        round = mock(Round.class);

        consumedSystemTransactions = new ArrayList<>();
        consumer = systemTransaction -> consumedSystemTransactions.add(systemTransaction);
        transaction = mock(TransactionWrapper.class);

        final Randotron randotron = Randotron.create();

        final var keysAndCerts =
                KeysAndCerts.generate(NodeId.FIRST_NODE_ID, EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY, new PublicStores());

        final var signer = new PlatformSigner(keysAndCerts);
        final Hash stateHash = randotron.nextHash();
        final Bytes signature = signer.signImmutable(stateHash);

        stateSignatureTransaction = StateSignatureTransaction.newBuilder()
                .round(1000L)
                .signature(signature)
                .hash(stateHash.getBytes())
                .build();

        final var platform = mock(Platform.class);
        final var initTrigger = mock(InitTrigger.class);
        final var softwareVersion = mock(SemanticVersion.class);
        final var platformContext = mock(PlatformContext.class);
        final var config = ConfigurationBuilder.create()
                .withConfigDataType(StressTestingToolConfig.class)
                .build();
        final var metrics = mock(DefaultPlatformMetrics.class);
        final var cryptography = mock(MerkleCryptoEngine.class);
        when(platform.getContext()).thenReturn(platformContext);
        when(platformContext.getConfiguration()).thenReturn(config);
        when(platformContext.getMetrics()).thenReturn(metrics);
        when(platformContext.getMerkleCryptography()).thenReturn(cryptography);

        consensusStateEventHandler.onStateInitialized(state, platform, initTrigger, softwareVersion);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 440, 600})
    void handleConsensusRoundWithApplicationTransaction(final Integer transactionSize) {
        // Given
        givenRoundAndEvent();

        final var pool = new TransactionPool(1, transactionSize);

        when(transaction.getApplicationTransaction()).thenReturn(Bytes.wrap(pool.transaction()));

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
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

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
        when(round.iterator()).thenReturn(List.of(event).iterator());
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(List.of(
                                (ConsensusTransaction) transaction,
                                secondConsensusTransaction,
                                thirdConsensusTransaction)
                        .iterator());

        final var stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions.size()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 440, 600})
    void preHandleConsensusRoundWithApplicationTransaction(final Integer transactionSize) {
        // Given
        givenRoundAndEvent();

        final var pool = new TransactionPool(1, transactionSize);

        final var eventCore = mock(EventCore.class);
        final var gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(Bytes.wrap(pool.transaction()))
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
    void onSealDefaultsToTrue() {
        // Given (empty)

        // When
        final boolean result = consensusStateEventHandler.onSealConsensusRound(round, state);

        // Then
        assertThat(result).isTrue();
    }

    private void givenRoundAndEvent() {
        when(event.getCreatorId()).thenReturn(new NodeId());
        when(event.getSoftwareVersion()).thenReturn(new SemanticVersion(1, 1, 1, "", ""));
        when(event.getConsensusTimestamp()).thenReturn(Instant.now());
        when(event.consensusTransactionIterator())
                .thenReturn(Collections.singletonList((ConsensusTransaction) transaction)
                        .iterator());
        when(round.iterator()).thenReturn(Collections.singletonList(event).iterator());
    }
}
