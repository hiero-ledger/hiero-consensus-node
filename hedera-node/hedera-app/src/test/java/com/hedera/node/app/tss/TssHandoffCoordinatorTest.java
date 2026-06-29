// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TssHandoffCoordinatorTest {
    private static final Bytes ADOPTED_ROSTER_HASH = Bytes.wrap("adopted-roster-hash");
    private static final Bytes ADDRESS_BOOK_HASH = Bytes.wrap("address-book-hash");
    private static final Bytes AGGREGATION_KEY = Bytes.wrap(new byte[49]);
    private static final Bytes SIGNATURE = Bytes.wrap("signature");
    private static final Bytes VERIFICATION_KEY = Bytes.wrap("verification-key");
    private static final Bytes OTHER_VERIFICATION_KEY = Bytes.wrap("other-verification-key");
    private static final Roster PREVIOUS_ROSTER = Roster.DEFAULT;
    private static final Roster ADOPTED_ROSTER = Roster.DEFAULT;
    private static final TssConfig WRAPS_TSS_CONFIG = HederaTestConfigBuilder.create()
            .withValue("tss.forceHandoffs", "true")
            .withValue("tss.wrapsEnabled", "true")
            .getOrCreateConfig()
            .getConfigData(TssConfig.class);

    @Mock
    private WritableHistoryStore historyStore;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private HistoryService historyService;

    @Mock
    private HintsService hintsService;

    @Test
    void promotesBothConstructionsWhenAggregateProofMatchesHintsVerificationKey() {
        final var proof = aggregateProof(VERIFICATION_KEY, VERIFICATION_KEY);
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));
        given(historyStore.handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true))
                .willReturn(true);
        given(hintsService.handoff(hintsStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true))
                .willReturn(true);

        assertTrue(TssHandoffCoordinator.tryForcedNonWrapsJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore).handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true);
        verify(hintsService).handoff(hintsStore, PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, true);
        verify(historyService).setLatestHistoryProof(proof);
    }

    @Test
    void skipsBothConstructionsWhenTargetMetadataDoesNotMatchHintsVerificationKey() {
        final var proof = aggregateProof(OTHER_VERIFICATION_KEY, VERIFICATION_KEY);
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));

        assertFalse(TssHandoffCoordinator.tryForcedNonWrapsJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore, never()).handoff(any(Roster.class), any(Roster.class), any(Bytes.class), eq(true));
        verify(hintsService, never())
                .handoff(
                        any(WritableHintsStore.class),
                        any(Roster.class),
                        any(Roster.class),
                        any(Bytes.class),
                        eq(true));
        verify(historyService, never()).setLatestHistoryProof(any());
    }

    @Test
    void skipsBothConstructionsWhenProofHasNoAggregateWitness() {
        final var proof = HistoryProof.newBuilder()
                .targetHistory(
                        History.newBuilder().addressBookHash(ADDRESS_BOOK_HASH).metadata(VERIFICATION_KEY))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("wraps-proof")))
                .build();
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));

        assertFalse(TssHandoffCoordinator.tryForcedNonWrapsJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH));

        verify(historyStore, never()).handoff(any(Roster.class), any(Roster.class), any(Bytes.class), eq(true));
        verify(hintsService, never())
                .handoff(
                        any(WritableHintsStore.class),
                        any(Roster.class),
                        any(Roster.class),
                        any(Bytes.class),
                        eq(true));
        verify(historyService, never()).setLatestHistoryProof(any());
    }

    @Test
    void wrapsJointHandoffSkipsHintsWhenHistoryDoesNotPromote() {
        final var proof = HistoryProof.newBuilder()
                .targetHistory(
                        History.newBuilder().addressBookHash(ADDRESS_BOOK_HASH).metadata(VERIFICATION_KEY))
                .uncompressedWrapsProof(Bytes.wrap("uncompressed-wraps-proof"))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder().wrapsProof(Bytes.wrap("wraps-proof")))
                .build();
        given(hintsStore.getNextConstruction()).willReturn(hintsConstruction(VERIFICATION_KEY));
        given(historyStore.getNextConstruction()).willReturn(historyConstruction(proof));
        given(historyStore.handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, false))
                .willReturn(false);

        assertFalse(TssHandoffCoordinator.tryForcedJointHandoff(
                historyStore,
                hintsStore,
                historyService,
                hintsService,
                PREVIOUS_ROSTER,
                ADOPTED_ROSTER,
                ADOPTED_ROSTER_HASH,
                WRAPS_TSS_CONFIG));

        verify(historyStore).handoff(PREVIOUS_ROSTER, ADOPTED_ROSTER, ADOPTED_ROSTER_HASH, false);
        verify(hintsService, never())
                .handoff(
                        any(WritableHintsStore.class),
                        any(Roster.class),
                        any(Roster.class),
                        any(Bytes.class),
                        eq(true));
        verify(historyService, never()).setLatestHistoryProof(any());
    }

    private static HintsConstruction hintsConstruction(final Bytes verificationKey) {
        return HintsConstruction.newBuilder()
                .constructionId(2L)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(AGGREGATION_KEY, verificationKey), List.of()))
                .build();
    }

    private static HistoryProofConstruction historyConstruction(final HistoryProof proof) {
        return HistoryProofConstruction.newBuilder()
                .constructionId(3L)
                .targetRosterHash(Bytes.wrap("constructed-roster-hash"))
                .targetProof(proof)
                .build();
    }

    private static HistoryProof aggregateProof(final Bytes targetMetadata, final Bytes aggregateVerificationKey) {
        return HistoryProof.newBuilder()
                .targetHistory(
                        History.newBuilder().addressBookHash(ADDRESS_BOOK_HASH).metadata(targetMetadata))
                .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                        .aggregatedNodeSignatures(
                                new AggregatedNodeSignatures(SIGNATURE, List.of(1L), aggregateVerificationKey)))
                .build();
    }
}
