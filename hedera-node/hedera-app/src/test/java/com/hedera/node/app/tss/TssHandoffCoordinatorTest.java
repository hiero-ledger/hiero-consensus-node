// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
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
