// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TssHandoffCoordinatorTest {
    private static final Bytes ADOPTED_ROSTER_HASH = Bytes.wrap("adopted-roster-hash");
    private static final Bytes ADDRESS_BOOK_HASH = Bytes.wrap("address-book-hash");
    private static final Bytes AGGREGATION_KEY = Bytes.wrap(new byte[49]);
    private static final Bytes VERIFICATION_KEY = Bytes.wrap("verification-key");
    private static final Bytes OTHER_VERIFICATION_KEY = Bytes.wrap("other-verification-key");
    private static final Roster PREVIOUS_ROSTER = Roster.DEFAULT;
    private static final Roster ADOPTED_ROSTER = Roster.DEFAULT;

    @Mock
    private WritableHistoryStore historyStore;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private HistoryService historyService;

    @Mock
    private HintsService hintsService;

    @BeforeEach
    void readyStores() {
        lenient().when(hintsStore.getCrsState()).thenReturn(CRSState.DEFAULT);
        lenient().when(hintsStore.isReadyToAdopt(ADOPTED_ROSTER_HASH)).thenReturn(true);
        lenient().when(historyStore.isReadyToAdopt(ADOPTED_ROSTER_HASH)).thenReturn(true);
    }

    @Test
    void refusesHandoffWhenCrsPrerequisiteIsIncomplete() {
        given(hintsStore.isReadyToAdopt(ADOPTED_ROSTER_HASH)).willReturn(false);
        assertFalse(TssHandoffCoordinator.tryJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));
        verify(historyStore, never()).handoff(any(), any(), any());
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void refusesCapacityRegressionBeforeHistoryPromotion() {
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(hintsStore.getCrsState())
                .willReturn(CRSState.newBuilder().numParties(16).build());
        assertFalse(TssHandoffCoordinator.tryJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));
        verify(historyStore, never()).handoff(any(), any(), any());
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void retainsActiveForTransportOverrideButRejectsUnpreparedMembershipOrWeights() {
        final var previous = rosterWithWeights(1L, 1L, 1L);
        final var active = activeBoundHints()
                .copyBuilder()
                .targetRosterHash(RosterUtils.hash(previous).getBytes())
                .build();
        given(hintsStore.getActiveConstruction()).willReturn(active);
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        assertTrue(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore,
                historyStore,
                previous,
                previous.copyBuilder().build(),
                Bytes.wrap("different transport hash"),
                false));
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, rosterWithWeights(1L, 2L, 1L), Bytes.wrap("new weights"), false));
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore,
                historyStore,
                previous,
                rosterWithWeights(1L, 1L, 1L, 1L),
                Bytes.wrap("new member"),
                false));
    }

    @Test
    void resolvesPreparedTargetOnlyFromCompleteRosterWithIdenticalWeightedMembers() {
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var preparedHash = RosterUtils.hash(prepared).getBytes();
        final var adopted = withNewCertificate(prepared);
        final var adoptedHash = RosterUtils.hash(adopted).getBytes();
        assertEquals(
                preparedHash,
                TssHandoffCoordinator.preparedRosterHashForAdoption(preparedHash, adopted, adoptedHash, prepared)
                        .orElseThrow());
        assertEquals(
                adoptedHash,
                TssHandoffCoordinator.preparedRosterHashForAdoption(adoptedHash, adopted, adoptedHash, null)
                        .orElseThrow());
        assertTrue(TssHandoffCoordinator.preparedRosterHashForAdoption(preparedHash, adopted, adoptedHash, null)
                .isEmpty());
        assertTrue(TssHandoffCoordinator.preparedRosterHashForAdoption(preparedHash, adopted, adoptedHash, adopted)
                .isEmpty());
        final var reweighted = rosterWithWeights(10L, 20L, 31L);
        assertTrue(TssHandoffCoordinator.preparedRosterHashForAdoption(
                        preparedHash, reweighted, RosterUtils.hash(reweighted).getBytes(), prepared)
                .isEmpty());
        final var withNewMember = rosterWithWeights(10L, 20L, 30L, 1L);
        assertTrue(TssHandoffCoordinator.preparedRosterHashForAdoption(
                        preparedHash,
                        withNewMember,
                        RosterUtils.hash(withNewMember).getBytes(),
                        prepared)
                .isEmpty());
    }

    @Test
    void retainsPreparedWeightRotationWhenOverrideChangesTransportAfterEarlyPromotion() {
        final var previous = rosterWithWeights(1L, 1L, 1L);
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var adopted = withNewCertificate(prepared);
        final var adoptedHash = RosterUtils.hash(adopted).getBytes();
        given(hintsStore.getActiveConstruction())
                .willReturn(activeBoundHints()
                        .copyBuilder()
                        .targetRosterHash(RosterUtils.hash(prepared).getBytes())
                        .build());
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        given(historyStore.getActiveConstruction())
                .willReturn(historyConstruction(wrapsProof(VERIFICATION_KEY))
                        .copyBuilder()
                        .targetRosterHash(RosterUtils.hash(prepared).getBytes())
                        .build());

        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, adopted, adoptedHash, false));
        assertTrue(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, adopted, adoptedHash, true, prepared));

        given(historyStore.getActiveConstruction())
                .willReturn(historyConstruction(wrapsProof(OTHER_VERIFICATION_KEY))
                        .copyBuilder()
                        .targetRosterHash(RosterUtils.hash(prepared).getBytes())
                        .build());
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, adopted, adoptedHash, true, prepared));
        given(hintsStore.getCrsState())
                .willReturn(completedCrs()
                        .copyBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .build());
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, adopted, adoptedHash, false, prepared));
    }

    @Test
    void previousPlatformWeightsCannotAuthorizeRetainingAnAlreadyReweightedScheme() {
        final var previous = rosterWithWeights(1L, 1L, 1L);
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var adopted = withNewCertificate(previous);
        final var adoptedHash = RosterUtils.hash(adopted).getBytes();
        given(hintsStore.getActiveConstruction())
                .willReturn(activeBoundHints()
                        .copyBuilder()
                        .targetRosterHash(RosterUtils.hash(prepared).getBytes())
                        .build());
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, previous, adopted, adoptedHash, false, prepared));
    }

    private static Roster withNewCertificate(final Roster roster) {
        return new Roster(roster.rosterEntries().stream()
                .map(entry -> entry.copyBuilder()
                        .gossipCaCertificate(Bytes.wrap("new certificate"))
                        .build())
                .toList());
    }

    @Test
    void repeatedOverridesRetainFullMembershipEvidenceAfterPreparedRosterIsPruned() {
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var active = new AtomicReference<>(activeBoundHints()
                .copyBuilder()
                .targetRosterHash(RosterUtils.hash(prepared).getBytes())
                .build());
        given(hintsStore.getActiveConstruction()).willAnswer(ignore -> active.get());
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        given(hintsStore.rebindActiveTargetRosterHash(any(), any())).willAnswer(invocation -> {
            active.set(active.get()
                    .copyBuilder()
                    .targetRosterHash(invocation.getArgument(1))
                    .build());
            return true;
        });
        var previous = rosterWithWeights(1L, 1L, 1L);
        for (int i = 0; i < 3; i++) {
            final var certificate = Bytes.wrap("certificate " + i);
            final var adopted = new Roster(prepared.rosterEntries().stream()
                    .map(entry ->
                            entry.copyBuilder().gossipCaCertificate(certificate).build())
                    .toList());
            final var adoptedHash = RosterUtils.hash(adopted).getBytes();
            assertTrue(TssHandoffCoordinator.rebindActiveTargetsForAdoption(
                    hintsStore, historyStore, previous, adopted, adoptedHash, false, i == 0 ? prepared : null));
            assertEquals(adoptedHash, active.get().targetRosterHash());
            previous = adopted;
        }
        verify(hintsStore, times(3)).rebindActiveTargetRosterHash(any(), any());
        verify(historyStore, never()).rebindActiveTargetRosterHash(any(), any());
    }

    @Test
    void refusesRebindingBothServicesBeforeEitherMutatesWhenActiveTargetsDiffer() {
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var preparedHash = RosterUtils.hash(prepared).getBytes();
        final var adopted = withNewCertificate(prepared);
        final var adoptedHash = RosterUtils.hash(adopted).getBytes();
        given(hintsStore.getActiveConstruction())
                .willReturn(activeBoundHints()
                        .copyBuilder()
                        .targetRosterHash(preparedHash)
                        .build());
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        given(historyStore.getActiveConstruction()).willReturn(historyConstruction(wrapsProof(VERIFICATION_KEY)));
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, prepared, adopted, adoptedHash, true, prepared));
        assertFalse(TssHandoffCoordinator.rebindActiveTargetsForAdoption(
                hintsStore, historyStore, prepared, adopted, adoptedHash, true, prepared));
        verify(hintsStore, never()).rebindActiveTargetRosterHash(any(), any());
        verify(historyStore, never()).rebindActiveTargetRosterHash(any(), any());
    }

    @Test
    void rebindsBothCompletedTargetsAfterFullRosterVerification() {
        final var prepared = rosterWithWeights(10L, 20L, 30L);
        final var preparedHash = RosterUtils.hash(prepared).getBytes();
        final var adopted = withNewCertificate(prepared);
        final var adoptedHash = RosterUtils.hash(adopted).getBytes();
        given(hintsStore.getActiveConstruction())
                .willReturn(activeBoundHints()
                        .copyBuilder()
                        .targetRosterHash(preparedHash)
                        .build());
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        given(historyStore.getActiveConstruction())
                .willReturn(historyConstruction(wrapsProof(VERIFICATION_KEY))
                        .copyBuilder()
                        .targetRosterHash(preparedHash)
                        .build());
        given(hintsStore.rebindActiveTargetRosterHash(preparedHash, adoptedHash))
                .willReturn(true);
        given(historyStore.rebindActiveTargetRosterHash(preparedHash, adoptedHash))
                .willReturn(true);
        assertTrue(TssHandoffCoordinator.rebindActiveTargetsForAdoption(
                hintsStore, historyStore, prepared, adopted, adoptedHash, true, prepared));
        verify(hintsStore).rebindActiveTargetRosterHash(preparedHash, adoptedHash);
        verify(historyStore).rebindActiveTargetRosterHash(preparedHash, adoptedHash);
    }

    @Test
    void retainedConstructionRequiresMatchingProofAndCompletedCrs() {
        final var active = activeBoundHints();
        given(hintsStore.getActiveConstruction()).willReturn(active);
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        given(historyStore.getActiveConstruction()).willReturn(historyConstruction(wrapsProof(VERIFICATION_KEY)));
        assertTrue(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true));
        given(historyStore.getActiveConstruction()).willReturn(historyConstruction(wrapsProof(OTHER_VERIFICATION_KEY)));
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true));
        given(hintsStore.getCrsState())
                .willReturn(completedCrs()
                        .copyBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .build());
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, false));
    }

    @Test
    void importedConstructionRequiresMatchingTargetAndEnoughCapacity() {
        final var active = activeBoundHints();
        given(hintsStore.getActiveConstruction()).willReturn(active);
        given(hintsStore.getCrsState()).willReturn(completedCrs());
        assertTrue(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore,
                historyStore,
                rosterWithWeights(1L),
                rosterWithWeights(1L, 1L, 1L),
                ADOPTED_ROSTER_HASH,
                false));
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore,
                historyStore,
                rosterWithWeights(1L),
                rosterWithWeights(1L, 1L, 1L, 1L, 1L, 1L, 1L),
                ADOPTED_ROSTER_HASH,
                false));
        given(hintsStore.getCrsState())
                .willReturn(completedCrs().copyBuilder().ceremonyId(2L).build());
        assertFalse(TssHandoffCoordinator.canRetainActiveConstruction(
                hintsStore, historyStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, false));
    }

    private static HintsConstruction activeBoundHints() {
        return hintsConstruction(VERIFICATION_KEY).copyBuilder().crsId(1L).build();
    }

    private static CRSState completedCrs() {
        return CRSState.newBuilder()
                .ceremonyId(1L)
                .numParties(8)
                .stage(CRSStage.COMPLETED)
                .crs(Bytes.wrap(new byte[304 + 288 * 8]))
                .build();
    }

    private static Roster rosterWithWeights(final long... weights) {
        return new Roster(IntStream.range(0, weights.length)
                .mapToObj(i ->
                        RosterEntry.newBuilder().nodeId(i).weight(weights[i]).build())
                .toList());
    }

    @Test
    void usesJointForcedHandoffOnlyWhenForcingHandoffsWithBothHintsAndHistory() {
        assertTrue(TssHandoffCoordinator.usesJointForcedHandoff(tssConfig(true, true, true)));
        assertFalse(TssHandoffCoordinator.usesJointForcedHandoff(tssConfig(false, true, true)));
        assertFalse(TssHandoffCoordinator.usesJointForcedHandoff(tssConfig(true, false, true)));
        assertFalse(TssHandoffCoordinator.usesJointForcedHandoff(tssConfig(true, true, false)));
    }

    @Test
    void promotesBothConstructionsWhenProofMatchesHintsVerificationKey() {
        final var proof = wrapsProof(VERIFICATION_KEY);
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));
        given(historyStore.handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH))
                .willReturn(true);
        given(hintsService.handoff(hintsStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true))
                .willReturn(true);

        assertTrue(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore).handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH);
        verify(hintsService).handoff(hintsStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true);
        verify(historyService).setLatestHistoryProof(proof);
    }

    @Test
    void skipsBothConstructionsWhenTargetMetadataDoesNotMatchHintsVerificationKey() {
        final var proof = wrapsProof(OTHER_VERIFICATION_KEY);
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));

        assertFalse(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore, never()).handoff(any(), any(), any());
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
        verify(historyService, never()).setLatestHistoryProof(any());
    }

    @Test
    void skipsBothConstructionsWhenHistoryConstructionIsIncomplete() {
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction())
                .willReturn(
                        HistoryProofConstruction.newBuilder().constructionId(3L).build());

        assertFalse(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore, never()).handoff(any(), any(), any());
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void skipsBothConstructionsWhenHintsConstructionIsIncomplete() {
        given(hintsStore.getNextConstruction())
                .willReturn(HintsConstruction.newBuilder().constructionId(2L).build());
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(wrapsProof(VERIFICATION_KEY)));

        assertFalse(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore, never()).handoff(any(), any(), any());
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void skipsHintsWhenHistoryDoesNotPromote() {
        final var proof = wrapsProof(VERIFICATION_KEY);
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));
        given(historyStore.handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH))
                .willReturn(false);

        assertFalse(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore).handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH);
        verify(hintsService, never()).handoff(any(), any(), any(), any(), anyBoolean());
        verify(historyService, never()).setLatestHistoryProof(any());
    }

    private static TssConfig tssConfig(
            final boolean hintsEnabled, final boolean historyEnabled, final boolean forceHandoffs) {
        return HederaTestConfigBuilder.create()
                .withValue("tss.hintsEnabled", "" + hintsEnabled)
                .withValue("tss.historyEnabled", "" + historyEnabled)
                .withValue("tss.forceHandoffs", "" + forceHandoffs)
                .getOrCreateConfig()
                .getConfigData(TssConfig.class);
    }

    private static HintsConstruction hintsConstruction(final Bytes verificationKey) {
        return HintsConstruction.newBuilder()
                .constructionId(2L)
                .targetRosterHash(ADOPTED_ROSTER_HASH)
                .numParties(8)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(AGGREGATION_KEY, verificationKey), List.of()))
                .build();
    }

    private static HistoryProofConstruction historyConstruction(final HistoryProof proof) {
        return HistoryProofConstruction.newBuilder()
                .constructionId(3L)
                .targetRosterHash(ADOPTED_ROSTER_HASH)
                .targetProof(proof)
                .build();
    }

    private static HistoryProof wrapsProof(final Bytes targetMetadata) {
        return HistoryProof.newBuilder()
                .targetHistory(
                        History.newBuilder().addressBookHash(ADDRESS_BOOK_HASH).metadata(targetMetadata))
                .uncompressedWrapsProof(Bytes.wrap("uncompressed-wraps-proof"))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("wraps-proof")))
                .build();
    }
}
