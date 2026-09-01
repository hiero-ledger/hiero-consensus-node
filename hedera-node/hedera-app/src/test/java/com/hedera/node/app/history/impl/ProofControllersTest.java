// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.history.impl.ProofControllers.activeProofNeedsWork;
import static com.hedera.node.app.history.impl.ProofControllers.groundsGenesisProof;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
    private static final String KEY_HASH_A = "0a".repeat(48);
    private static final String KEY_HASH_B = "0b".repeat(48);

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
    private TssConfig tssConfig;

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

        assertTrue(subject.getAnyInProgress(tssConfig).isEmpty());
        final var firstController = subject.getOrCreateFor(
                activeRosters,
                ONE_CONSTRUCTION,
                historyStore,
                HintsConstruction.DEFAULT,
                HistoryProofConstruction.DEFAULT,
                DEFAULT_CONFIG.getConfigData(TssConfig.class));
        assertTrue(subject.getAnyInProgress(tssConfig).isEmpty());
        assertTrue(subject.getInProgressById(1L, tssConfig).isEmpty());
        assertTrue(subject.getInProgressById(2L, tssConfig).isEmpty());
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
    void nothingIsFoldableWithoutAnUncompressedWrapsProof() {
        assertFalse(ProofControllers.isFoldable(null, tssConfigWithProvingKeyHash(KEY_HASH_A)));
        assertFalse(ProofControllers.isFoldable(
                HistoryProof.newBuilder()
                        .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                                .aggregatedNodeSignatures(AggregatedNodeSignatures.DEFAULT))
                        .build(),
                tssConfigWithProvingKeyHash(KEY_HASH_A)));
    }

    @Test
    void proofWithoutRecordedProvingKeyHashIsAssumedFoldable() {
        // An unrecorded hash is taken to be the configured one
        assertTrue(
                ProofControllers.isFoldable(wrapsProofBuiltWith(Bytes.EMPTY), tssConfigWithProvingKeyHash(KEY_HASH_A)));
    }

    @Test
    void proofIsFoldableOnlyUnderTheProvingKeyThatBuiltIt() {
        final var proof = wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A));

        assertTrue(ProofControllers.isFoldable(proof, tssConfigWithProvingKeyHash(KEY_HASH_A)));
        assertFalse(ProofControllers.isFoldable(proof, tssConfigWithProvingKeyHash(KEY_HASH_B)));
    }

    @Test
    void blankConfiguredProvingKeyHashDoesNotMatchARecordedOne() {
        final var proof = wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A));

        assertEquals(Bytes.EMPTY, ProofControllers.configuredProvingKeyHash(tssConfigWithProvingKeyHash("")));
        assertFalse(ProofControllers.isFoldable(proof, tssConfigWithProvingKeyHash("")));
    }

    @Test
    void abandoningAValidProofNeedsTheOperatorGateButBootstrappingDoesNot() {
        final var noWrapsProof = HistoryProof.newBuilder()
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().aggregatedNodeSignatures(AggregatedNodeSignatures.DEFAULT))
                .build();
        final var staleKeyProof = wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A));

        // With nothing to fold onto, a genesis proof is grounded whatever the property says
        assertTrue(ProofControllers.needsFreshGenesis(noWrapsProof, tssConfigFor(KEY_HASH_B, false), false));
        assertTrue(ProofControllers.needsFreshGenesis(noWrapsProof, tssConfigFor(KEY_HASH_B, true), false));

        // A WRAPS-extensible proof is only discarded when the property allows it
        assertFalse(ProofControllers.needsFreshGenesis(staleKeyProof, tssConfigFor(KEY_HASH_B, false), false));
        assertTrue(ProofControllers.needsFreshGenesis(staleKeyProof, tssConfigFor(KEY_HASH_B, true), false));

        // And never when the proof still folds
        assertFalse(ProofControllers.needsFreshGenesis(staleKeyProof, tssConfigFor(KEY_HASH_A, true), false));
    }

    @Test
    void aValidProofIsNeverAbandonedOnceBlockProofsCarryTheChainOfTrust() {
        final var staleKeyProof = wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A));

        // Grounding a new proof moves the ledger id, which verifiers cannot follow after the cutover
        assertFalse(ProofControllers.needsFreshGenesis(staleKeyProof, tssConfigFor(KEY_HASH_B, true), true));

        // But a chain of trust that does not exist yet is still grounded
        final var noWrapsProof = HistoryProof.newBuilder()
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().aggregatedNodeSignatures(AggregatedNodeSignatures.DEFAULT))
                .build();
        assertTrue(ProofControllers.needsFreshGenesis(noWrapsProof, tssConfigFor(KEY_HASH_B, true), true));
    }

    @Test
    void nothingNeedsFreshGenesisWhenWrapsIsDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(TssConfig.class)
                .withValue("tss.wrapsEnabled", "false")
                .withValue("tss.wrapsProvingKeyHash", KEY_HASH_B)
                .withValue("tss.wrapsAllowFreshGenesisOnKeyChange", "true")
                .getOrCreateConfig()
                .getConfigData(TssConfig.class);

        assertFalse(ProofControllers.needsFreshGenesis(wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A)), config, false));
        assertFalse(ProofControllers.needsFreshGenesis(null, config, false));
    }

    @Test
    void activeProofNeedsWorkUntilThereIsAFoldableProofOfTheRightKind() {
        final var foldable = constructionWith(wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A)));
        final var staleKey = constructionWith(wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_B)));
        final var nonRecursive = constructionWith(HistoryProof.newBuilder()
                .chainOfTrustProof(
                        ChainOfTrustProof.newBuilder().aggregatedNodeSignatures(AggregatedNodeSignatures.DEFAULT))
                .build());

        // Nothing to build on yet
        assertTrue(activeProofNeedsWork(HistoryProofConstruction.DEFAULT, tssConfigFor(KEY_HASH_A, false), false));
        // A proof of the wrong kind for the current WRAPS setting
        assertTrue(activeProofNeedsWork(nonRecursive, tssConfigFor(KEY_HASH_A, false), false));
        // A proof that can no longer be folded onto, once re-anchoring is allowed
        assertTrue(activeProofNeedsWork(staleKey, tssConfigFor(KEY_HASH_A, true), false));

        // ...but not while re-anchoring is disallowed, nor once block proofs carry the chain of trust
        assertFalse(activeProofNeedsWork(staleKey, tssConfigFor(KEY_HASH_A, false), false));
        assertFalse(activeProofNeedsWork(staleKey, tssConfigFor(KEY_HASH_A, true), true));
        // ...and never for a proof that still folds
        assertFalse(activeProofNeedsWork(foldable, tssConfigFor(KEY_HASH_A, true), false));
    }

    @Test
    void groundsGenesisProofBeforeAnyLedgerIdAndWheneverAFreshGenesisIsNeeded() {
        final var foldable = constructionWith(wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_A)));
        final var staleKey = constructionWith(wrapsProofBuiltWith(Bytes.fromHex(KEY_HASH_B)));

        // No ledger id yet: the network is grounding its first chain of trust
        assertTrue(groundsGenesisProof(HistoryProofConstruction.DEFAULT, null, tssConfigFor(KEY_HASH_A, false), false));
        // A superseded proving key grounds a new one, when allowed
        assertTrue(groundsGenesisProof(staleKey, LEDGER_ID, tssConfigFor(KEY_HASH_A, true), false));

        // An extendable chain proves the NEXT construction's key instead
        assertFalse(groundsGenesisProof(foldable, LEDGER_ID, tssConfigFor(KEY_HASH_A, true), false));
        // A construction with no proof yet is not itself grounding one
        assertFalse(groundsGenesisProof(
                HistoryProofConstruction.DEFAULT, LEDGER_ID, tssConfigFor(KEY_HASH_A, true), false));
    }

    @Test
    void reAnchoredLedgerIdIsNullWhenTheAnchorHasNotMoved() {
        final var anchor = Bytes.wrap("ADDRESS_BOOK_HASH");
        final var proof = HistoryProof.newBuilder()
                .targetHistory(new History(anchor, Bytes.EMPTY))
                .build();

        // Grounding at the same address book anchors at the same hash, so there is nothing to publish
        assertNull(ProofControllers.reAnchoredLedgerId(proof, anchor));
        // Grounding anywhere else establishes a new ledger id, including the very first one
        assertEquals(anchor, ProofControllers.reAnchoredLedgerId(proof, Bytes.wrap("SOMETHING_ELSE")));
        assertEquals(anchor, ProofControllers.reAnchoredLedgerId(proof, null));
    }

    private static HistoryProofConstruction constructionWith(final HistoryProof proof) {
        return HistoryProofConstruction.newBuilder()
                .constructionId(1L)
                .targetProof(proof)
                .build();
    }

    private static HistoryProof wrapsProofBuiltWith(final Bytes provingKeyHash) {
        return HistoryProof.newBuilder()
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("COMPRESSED")))
                .uncompressedWrapsProof(Bytes.wrap("UNCOMPRESSED"))
                .wrapsProvingKeyHash(provingKeyHash)
                .build();
    }

    private static TssConfig tssConfigWithProvingKeyHash(final String hashHex) {
        return tssConfigFor(hashHex, false);
    }

    private static TssConfig tssConfigFor(final String hashHex, final boolean allowFreshGenesisOnKeyChange) {
        return HederaTestConfigBuilder.create()
                .withConfigDataType(TssConfig.class)
                .withValue("tss.wrapsEnabled", "true")
                .withValue("tss.wrapsProvingKeyHash", hashHex)
                .withValue("tss.wrapsAllowFreshGenesisOnKeyChange", "" + allowFreshGenesisOnKeyChange)
                .getOrCreateConfig()
                .getConfigData(TssConfig.class);
    }

    private void setController(final ProofController controller) throws Exception {
        final var field = ProofControllers.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(subject, controller);
    }
}
