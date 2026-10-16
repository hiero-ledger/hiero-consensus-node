// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.impl.ProofControllers.activeProofNeedsWork;
import static com.hedera.node.app.history.impl.ProofControllers.freshGenesisInProgress;
import static com.hedera.node.app.history.impl.ProofControllers.freshGenesisRequested;
import static com.hedera.node.app.history.impl.ProofControllers.groundsChainOfTrust;
import static com.hedera.node.app.history.impl.ProofControllers.groundsGenesisProof;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllersTest {
    private static final ProofKeysAccessorImpl.SchnorrKeyPair MOCK_KEY_PAIR =
            new ProofKeysAccessorImpl.SchnorrKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final HistoryProofConstruction ONE_CONSTRUCTION =
            HistoryProofConstruction.newBuilder().constructionId(1L).build();
    private static final Bytes LEDGER_ID = Bytes.wrap("LEDGER_ID");
    private static final Bytes A_ROSTER_HASH = Bytes.wrap("A");
    private static final Bytes B_ROSTER_HASH = Bytes.wrap("B");
    private static final HistoryProof WRAPS_PROOF = HistoryProof.newBuilder()
            .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("COMPRESSED")))
            .uncompressedWrapsProof(Bytes.wrap("UNCOMPRESSED"))
            .build();

    @Mock
    private Executor executor;

    @Mock
    private ProofKeysAccessor keyAccessor;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistoryService historyService;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private HistoryProofMetrics historyProofMetrics;

    @Mock
    private WrapsMpcStateMachine machine;

    @Mock
    private Supplier<NodeInfo> selfNodeInfoSupplier;

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private ReadableHistoryStore historyStore;

    @Mock
    private ProofController controller;

    private ProofControllers subject;

    @BeforeEach
    void setUp() {
        subject = new ProofControllers(
                executor,
                keyAccessor,
                library,
                submissions,
                selfNodeInfoSupplier,
                historyService,
                historyProofMetrics,
                machine);
    }

    @Test
    void getsAndCreatesInertControllersAsExpected() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);

        final var twoConstruction =
                HistoryProofConstruction.newBuilder().constructionId(2L).build();

        assertTrue(subject.getAnyInProgress().isEmpty());
        final var firstController = subject.getOrCreateFor(
                activeRosters,
                ONE_CONSTRUCTION,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));
        assertTrue(subject.getAnyInProgress().isEmpty());
        assertTrue(subject.getInProgressById(1L).isEmpty());
        assertTrue(subject.getInProgressById(2L).isEmpty());
        assertInstanceOf(InertProofController.class, firstController);
        final var secondController = subject.getOrCreateFor(
                activeRosters,
                twoConstruction,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));
        assertNotSame(firstController, secondController);
        assertInstanceOf(InertProofController.class, secondController);
    }

    @Test
    void returnsActiveControllerWhenSourceNodesHaveTargetThresholdWeight() {
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        given(weights.sourceNodesHaveTargetThreshold()).willReturn(true);
        given(keyAccessor.getOrCreateSchnorrKeyPair(1L)).willReturn(MOCK_KEY_PAIR);
        given(selfNodeInfoSupplier.get()).willReturn(selfNodeInfo);

        final var controller = subject.getOrCreateFor(
                activeRosters,
                ONE_CONSTRUCTION,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));

        assertInstanceOf(ProofControllerImpl.class, controller);
    }

    @Test
    void stopCancelsAndRefreshesControllerForSameConstructionId() throws Exception {
        final var learnedConstruction = HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .assemblyStartTime(asTimestamp(Instant.EPOCH))
                .build();
        given(controller.constructionId()).willReturn(1L);
        setController(controller);

        final var staleController = subject.getOrCreateFor(
                activeRosters,
                learnedConstruction,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));
        assertSame(controller, staleController);

        subject.stop();
        subject.stop();

        verify(controller).cancelPendingWork();
        given(activeRosters.transitionWeights(null)).willReturn(weights);
        final var refreshedController = subject.getOrCreateFor(
                activeRosters,
                learnedConstruction,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));
        assertNotSame(staleController, refreshedController);
    }

    @Test
    void onlyAConstructionWithTheSameRosterAsSourceAndTargetGroundsAChainOfTrust() {
        assertTrue(groundsChainOfTrust(constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, null)));
        assertFalse(groundsChainOfTrust(constructionFor(A_ROSTER_HASH, B_ROSTER_HASH, null)));
        // A construction that does not exist yet grounds nothing
        assertFalse(groundsChainOfTrust(HistoryProofConstruction.DEFAULT));
    }

    @Test
    void freshGenesisIsRequestedOnlyInThePostUpgradeRoundWhileConfiguredAndBeforeTheCutover() {
        final var requested = configWith("tss.needsFreshGenesisWrapsProof", "true");

        assertTrue(freshGenesisRequested(tssConfig(requested), blockStreamConfig(requested), true));

        // Never outside the round that carries the post-upgrade work
        assertFalse(freshGenesisRequested(tssConfig(requested), blockStreamConfig(requested), false));
        // Never unless asked for
        final var notRequested = configWith("tss.needsFreshGenesisWrapsProof", "false");
        assertFalse(freshGenesisRequested(tssConfig(notRequested), blockStreamConfig(notRequested), true));
    }

    @Test
    void freshGenesisIsNeverRequestedOnceBlockProofsAreToCarryTheChainOfTrust() {
        // A fresh genesis proof may move the ledger id, which verifiers cannot follow after the cutover
        final var cutoverEnabled =
                configWith("tss.needsFreshGenesisWrapsProof", "true", "blockStream.enableCutover", "true");
        assertFalse(freshGenesisRequested(tssConfig(cutoverEnabled), blockStreamConfig(cutoverEnabled), true));

        final var signingWithChainOfTrust = configWith(
                "tss.needsFreshGenesisWrapsProof",
                "true",
                "tss.forceMockSignatures",
                "false",
                "blockStream.streamMode",
                "BLOCKS",
                "tss.hintsEnabled",
                "true",
                "tss.historyEnabled",
                "true");
        assertFalse(freshGenesisRequested(
                tssConfig(signingWithChainOfTrust), blockStreamConfig(signingWithChainOfTrust), true));
    }

    @Test
    void freshGenesisIsInProgressWhileTheNextConstructionGroundsAnIncompleteChainOfTrust() {
        assertTrue(freshGenesisInProgress(constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, null)));
        // Once complete, the fresh proof is the active one and there is nothing in progress
        assertFalse(freshGenesisInProgress(constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, WRAPS_PROOF)));
        // A transition to a new roster extends the chain rather than grounding one
        assertFalse(freshGenesisInProgress(constructionFor(A_ROSTER_HASH, B_ROSTER_HASH, null)));
        assertFalse(freshGenesisInProgress(HistoryProofConstruction.DEFAULT));
    }

    @Test
    void activeProofNeedsWorkUntilTheChainOfTrustIsSettled() {
        final var settled = constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, WRAPS_PROOF);
        final var freshGenesis = constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, null);
        final var transition = constructionFor(A_ROSTER_HASH, B_ROSTER_HASH, null);

        // Nothing to build on yet
        assertTrue(activeProofNeedsWork(HistoryProofConstruction.DEFAULT, HistoryProofConstruction.DEFAULT, false));
        // A fresh genesis proof requested this round, or still being built
        assertTrue(activeProofNeedsWork(settled, HistoryProofConstruction.DEFAULT, true));
        assertTrue(activeProofNeedsWork(settled, freshGenesis, false));

        // ...but a settled chain of trust with an ordinary transition in flight, or nothing at all, needs none
        assertFalse(activeProofNeedsWork(settled, transition, false));
        assertFalse(activeProofNeedsWork(settled, HistoryProofConstruction.DEFAULT, false));
    }

    @Test
    void groundsGenesisProofBeforeAnyLedgerIdAndWhileAFreshGenesisIsRequestedOrInProgress() {
        final var freshGenesis = constructionFor(A_ROSTER_HASH, A_ROSTER_HASH, null);
        final var transition = constructionFor(A_ROSTER_HASH, B_ROSTER_HASH, null);

        // No ledger id yet: the network is grounding its first chain of trust
        assertTrue(groundsGenesisProof(HistoryProofConstruction.DEFAULT, null, false));
        // As is a fresh genesis proof, from the round it is requested until it completes
        assertTrue(groundsGenesisProof(HistoryProofConstruction.DEFAULT, LEDGER_ID, true));
        assertTrue(groundsGenesisProof(freshGenesis, LEDGER_ID, false));

        // An extendable chain proves the NEXT construction's key instead
        assertFalse(groundsGenesisProof(transition, LEDGER_ID, false));
        assertFalse(groundsGenesisProof(HistoryProofConstruction.DEFAULT, LEDGER_ID, false));
    }

    private static HistoryProofConstruction constructionFor(
            final Bytes sourceRosterHash, final Bytes targetRosterHash, final HistoryProof targetProof) {
        final var builder = HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .sourceRosterHash(sourceRosterHash)
                .targetRosterHash(targetRosterHash);
        if (targetProof != null) {
            builder.targetProof(targetProof);
        }
        return builder.build();
    }

    private static Configuration configWith(final String... keysAndValues) {
        final var builder = HederaTestConfigBuilder.create()
                .withConfigDataType(TssConfig.class)
                .withConfigDataType(BlockStreamConfig.class);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            builder.withValue(keysAndValues[i], keysAndValues[i + 1]);
        }
        return builder.getOrCreateConfig();
    }

    private static TssConfig tssConfig(final Configuration config) {
        return config.getConfigData(TssConfig.class);
    }

    private static BlockStreamConfig blockStreamConfig(final Configuration config) {
        return config.getConfigData(BlockStreamConfig.class);
    }

    private void setController(final ProofController controller) throws Exception {
        final var field = ProofControllers.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(subject, controller);
    }
}
