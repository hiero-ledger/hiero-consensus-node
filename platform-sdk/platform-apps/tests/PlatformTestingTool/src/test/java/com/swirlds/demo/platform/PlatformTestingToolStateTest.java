// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema.ROSTER_STATES_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.RosterStateId;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.internal.MerkleCryptoEngine;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.fs.stresstest.proto.RandomBytesTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransactionWrapper;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class PlatformTestingToolStateTest {

    private static final String DEFAULT_CONFIG = "configs/FCM1KForTest.json";
    private static final String CONFIG_WITHOUT_APPEND_SIG = "configs/FCM1KForTestWithoutAppendSig.json";
    private static final byte[] EMPTY_ARRAY = new byte[] {};
    private PlatformTestingToolState state;
    private MockedStatic<ParameterProvider> parameterProvider;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer;
    private List<ScopedSystemTransaction<StateSignatureTransaction>> consumedSystemTransactions;
    private StateSignatureTransaction stateSignatureTransaction;
    private PlatformTestingToolMain main;
    private PlatformEvent platformEvent;
    private Transaction transaction;
    private Random random;
    private Round round;
    private Roster roster;
    private EventWindow eventWindow;

    @BeforeEach
    void setUp() throws KeyStoreException, KeyGeneratingException, NoSuchAlgorithmException, NoSuchProviderException {
        final PayloadCfgSimple payloadConfig = mock(PayloadCfgSimple.class);
        when(payloadConfig.isAppendSig()).thenReturn(true);
        state = mock(PlatformTestingToolState.class);
        final ExpectedFCMFamily expectedFCMFamily = mock(ExpectedFCMFamily.class);
        when(state.getStateExpectedMap()).thenReturn(expectedFCMFamily);
        main = new PlatformTestingToolMain();
        random = new Random();
        roster = new Roster(Collections.EMPTY_LIST);
        transaction = mock(TransactionWrapper.class);
        platformEvent = mock(PlatformEvent.class);
        eventWindow = new EventWindow(10, 5, 20, AncientMode.BIRTH_ROUND_THRESHOLD);

        consumedSystemTransactions = new ArrayList<>();
        consumer = systemTransaction -> consumedSystemTransactions.add(systemTransaction);

        when(platformEvent.getCreatorId()).thenReturn(new NodeId());
        when(platformEvent.getSoftwareVersion()).thenReturn(new SemanticVersion(1, 1, 1, "", ""));
        when(platformEvent.getConsensusTimestamp()).thenReturn(Instant.now());

        final Randotron randotron = Randotron.create();

        final KeysAndCerts keysAndCerts =
                KeysAndCerts.generate(NodeId.FIRST_NODE_ID, EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY, new PublicStores());

        final PlatformSigner signer = new PlatformSigner(keysAndCerts);
        final Hash stateHash = randotron.nextHash();
        final Bytes signature = signer.signImmutable(stateHash);

        stateSignatureTransaction = StateSignatureTransaction.newBuilder()
                .round(1000L)
                .signature(signature)
                .hash(stateHash.getBytes())
                .build();
    }

    @AfterEach
    void tearDown() {
        parameterProvider.close();
    }

    @Test
    void handleConsensusRoundWithApplicationTransactionOfRandomType() {
        // Given
        givenInitState(DEFAULT_CONFIG);
        givenRoundAndEvent();

        final TestTransactionWrapper testTransactionWrapper = getTransactionWithRandomType(300);
        when(transaction.getApplicationTransaction()).thenReturn(Bytes.wrap(testTransactionWrapper.toByteArray()));

        // When
        main.consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).isEmpty();
    }

    @Test
    void handleConsensusRoundWithSystemTransaction() {
        // Given
        givenInitState(DEFAULT_CONFIG);
        givenRoundAndEvent();

        final Bytes stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        main.consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).hasSize(1);
    }

    @Test
    void handleConsensusRoundWithDisabledAppendSig() {
        // Given
        givenInitState(CONFIG_WITHOUT_APPEND_SIG);
        givenRoundAndEvent();

        final Bytes stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        // When
        main.consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).hasSize(1);
    }

    @Test
    void handleConsensusRoundWithMultipleSystemTransaction() {
        // Given
        givenInitState(DEFAULT_CONFIG);

        final TransactionWrapper secondConsensusTransaction = mock(TransactionWrapper.class);
        final TransactionWrapper thirdConsensusTransaction = mock(TransactionWrapper.class);
        when(platformEvent.consensusTransactionIterator())
                .thenReturn(List.of(
                                (ConsensusTransaction) transaction,
                                secondConsensusTransaction,
                                thirdConsensusTransaction)
                        .iterator());
        when(platformEvent.transactionIterator())
                .thenReturn(List.of(transaction, secondConsensusTransaction, thirdConsensusTransaction)
                        .iterator());

        final Bytes stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);

        when(transaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(secondConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);
        when(thirdConsensusTransaction.getApplicationTransaction()).thenReturn(stateSignatureTransactionBytes);

        round = new ConsensusRound(
                roster,
                List.of(platformEvent),
                eventWindow,
                Mockito.mock(ConsensusSnapshot.class),
                false,
                Instant.now());

        // When
        main.consensusStateEventHandler.onHandleConsensusRound(round, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).hasSize(3);
    }

    @Test
    void preHandleConsensusRoundWithApplicationTransactionOfRandomType() {
        // Given
        givenInitState(DEFAULT_CONFIG);
        givenRoundAndEvent();

        final TestTransactionWrapper testTransactionWrapper = getTransactionWithRandomType(300);

        final EventCore eventCore = mock(EventCore.class);
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(Bytes.wrap(testTransactionWrapper.toByteArray()))
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        platformEvent = new PlatformEvent(gossipEvent);

        // When
        main.consensusStateEventHandler.onPreHandle(platformEvent, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).isEmpty();
    }

    @Test
    void preHandleConsensusRoundWithSystemTransaction() {
        // Given
        givenInitState(DEFAULT_CONFIG);
        givenRoundAndEvent();

        final Bytes stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);
        final EventCore eventCore = mock(EventCore.class);
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(stateSignatureTransactionBytes)
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        platformEvent = new PlatformEvent(gossipEvent);

        // When
        main.consensusStateEventHandler.onPreHandle(platformEvent, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).hasSize(1);
    }

    @Test
    void preHandleConsensusRoundWithMultipleSystemTransaction() {
        // Given
        givenInitState(DEFAULT_CONFIG);

        final Bytes stateSignatureTransactionBytes = main.encodeSystemTransaction(stateSignatureTransaction);

        final EventCore eventCore = mock(EventCore.class);
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .transactions(List.of(
                        stateSignatureTransactionBytes, stateSignatureTransactionBytes, stateSignatureTransactionBytes))
                .build();
        when(eventCore.timeCreated()).thenReturn(Timestamp.DEFAULT);
        platformEvent = new PlatformEvent(gossipEvent);

        // When
        main.consensusStateEventHandler.onPreHandle(platformEvent, state, consumer);

        // Then
        assertThat(consumedSystemTransactions).hasSize(3);
    }

    @Test
    void onSealDefaultsToTrue() {
        // Given
        givenInitState(DEFAULT_CONFIG);
        givenRoundAndEvent();

        // When
        final boolean result = main.consensusStateEventHandler.onSealConsensusRound(round, state);

        // Then
        assertThat(result).isTrue();
    }

    private void givenRoundAndEvent() {
        when(platformEvent.transactionIterator())
                .thenReturn(Collections.singletonList(transaction).iterator());
        when(platformEvent.consensusTransactionIterator())
                .thenReturn(Collections.singletonList((ConsensusTransaction) transaction)
                        .iterator());

        round = new ConsensusRound(
                roster,
                List.of(platformEvent),
                eventWindow,
                Mockito.mock(ConsensusSnapshot.class),
                false,
                Instant.now());
    }

    private TestTransactionWrapper getTransactionWithRandomType(final int transactionSize) {
        final byte[] transactionBytes = new byte[transactionSize];
        random.nextBytes(transactionBytes);

        final RandomBytesTransaction randomBytesTransaction = RandomBytesTransaction.newBuilder()
                .setIsInserSeq(false)
                .setData(ByteString.copyFrom(transactionBytes))
                .build();

        final TestTransaction testTransaction = TestTransaction.newBuilder()
                .setBytesTransaction(randomBytesTransaction)
                .build();

        return TestTransactionWrapper.newBuilder()
                .setTestTransactionRawBytes(ByteString.copyFrom(testTransaction.toByteArray()))
                .build();
    }

    private void givenInitState(final String config) {
        final NodeId nodeId = NodeId.of(1L);
        final Platform platform = mock(Platform.class);
        final InitTrigger initTrigger = InitTrigger.GENESIS;
        final PlatformContext platformContext = mock(PlatformContext.class);
        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(1).build();

        givenPlatform(platform, platformContext, nodeId);
        givenPlatformContextConfig(platformContext, config);

        parameterProvider = mockStatic(ParameterProvider.class);
        ParameterProvider parameterProviderInstance = mock(ParameterProvider.class);
        parameterProvider.when(ParameterProvider::getInstance).thenReturn(parameterProviderInstance);
        when(parameterProviderInstance.getParameters()).thenReturn(new String[] {config});

        state.initChildren();
        main.consensusStateEventHandler.onStateInitialized(state, platform, initTrigger, softwareVersion);
        main.init(platform, nodeId);
    }

    private void givenPlatform(final Platform platform, final PlatformContext platformContext, final NodeId nodeId) {
        final Future<Hash> futureHash = mock(Future.class);
        final MerkleCryptoEngine cryptography = mock(MerkleCryptoEngine.class);
        when(cryptography.digestTreeAsync(any())).thenReturn(futureHash);
        final NotificationEngine notificationEngine = mock(NotificationEngine.class);

        when(platform.getRoster()).thenReturn(roster);
        when(platform.getSelfId()).thenReturn(nodeId);
        when(platformContext.getMerkleCryptography()).thenReturn(cryptography);
        when(platform.getContext()).thenReturn(platformContext);
        when(platform.getNotificationEngine()).thenReturn(notificationEngine);
        when(platform.getContext()).thenReturn(platformContext);
    }

    private void givenPlatformContextConfig(final PlatformContext platformContext, final String config) {
        final WritableStates platformWritableStates = mock(MapWritableStates.class);
        final WritableSingletonState platformWritableSingletonState = mock(WritableSingletonState.class);
        when(platformWritableStates.getSingleton(PLATFORM_STATE_KEY)).thenReturn(platformWritableSingletonState);
        when(state.getWritableStates(PlatformStateService.NAME)).thenReturn(platformWritableStates);

        final WritableStates rosterWritableStates = mock(MapWritableStates.class);
        final WritableSingletonState rosterWritableSingletonState = mock(WritableSingletonState.class);
        when(rosterWritableStates.getSingleton(ROSTER_STATES_KEY)).thenReturn(rosterWritableSingletonState);
        when(state.getWritableStates(RosterStateId.NAME)).thenReturn(rosterWritableStates);

        final PayloadCfgSimple payloadConfig = mock(PayloadCfgSimple.class);
        when(payloadConfig.isAppendSig()).thenReturn(config.equals(DEFAULT_CONFIG));

        final RandomDelayCfg randomDelayCfg = mock(RandomDelayCfg.class);
        when(payloadConfig.getDelayCfg()).thenReturn(randomDelayCfg);

        final FCMFamily fcmFamily = mock(FCMFamily.class);
        when(fcmFamily.getAccountFCQMap()).thenReturn(new MerkleMap<>());

        when(state.getConfig()).thenReturn(payloadConfig);
        when(state.getFcmFamily()).thenReturn(fcmFamily);

        final Metric metric = mock(SpeedometerMetric.class);
        final Counter counter = mock(Counter.class);
        final RunningAverageMetric runningAverageMetric = mock(RunningAverageMetric.class);

        final DefaultPlatformMetrics metrics = mock(DefaultPlatformMetrics.class);
        when(metrics.getOrCreate(any()))
                .thenReturn(
                        metric,
                        metric,
                        metric,
                        metric,
                        metric,
                        metric,
                        metric,
                        metric,
                        metric,
                        counter,
                        metric,
                        runningAverageMetric,
                        metric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        runningAverageMetric,
                        metric,
                        metric,
                        metric);

        when(platformContext.getMetrics()).thenReturn(metrics);
    }
}
