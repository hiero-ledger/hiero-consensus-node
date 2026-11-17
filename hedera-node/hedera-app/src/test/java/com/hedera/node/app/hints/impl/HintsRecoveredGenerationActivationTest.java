// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.hapi.node.state.hints.CRSStage.COMPLETED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class HintsRecoveredGenerationActivationTest {
    private static final Bytes HASH = Bytes.wrap("generation");
    private static final HintsConstruction ACTIVE = HintsConstruction.newBuilder()
            .constructionId(1)
            .crsId(1)
            .numParties(16)
            .sourceRosterHash(HASH)
            .targetRosterHash(HASH)
            .hintsScheme(new HintsScheme(
                    new PreprocessedKeys(Bytes.wrap("ak"), Bytes.wrap("vk")),
                    LongStream.range(0, 7)
                            .mapToObj(id -> new NodePartyId(id, (int) id + 1, 1))
                            .toList()))
            .build();

    @Test
    void activatesDeferredSigningOnlyAfterTheCompleteHistorySnapshotIsAvailable() {
        final var fixture = new Fixture();
        fixture.reconcile(null);
        fixture.reconcile(
                HistoryProofConstruction.newBuilder().targetRosterHash(HASH).build());
        fixture.reconcile(fixture.completed
                .copyBuilder()
                .targetProof(fixture.completed
                        .targetProofOrThrow()
                        .copyBuilder()
                        .targetNodeWeights(List.of())
                        .build())
                .build());
        assertFalse(fixture.context.isReady());

        fixture.reconcile(fixture.completed);

        assertTrue(fixture.context.isReady());
        assertEquals(ACTIVE, fixture.context.activeConstruction());
        final var weights = HintsGenerationWeights.from(ACTIVE, fixture.completed);
        assertEquals(10, weights.totalWeight());
        assertEquals(7, weights.schemeWeight());
        fixture.reconcile(fixture.completed);
        assertEquals(ACTIVE, fixture.context.activeConstruction());
    }

    @Test
    void completedMismatchedSnapshotsFailClosedDuringHandoffEvenWhenSigningWasReady() {
        final var fixture = new Fixture();
        fixture.reconcile(fixture.completed);
        final var mismatches = List.of(
                fixture.completed
                        .copyBuilder()
                        .targetRosterHash(Bytes.wrap("other-generation"))
                        .build(),
                fixture.completed
                        .copyBuilder()
                        .targetProof(fixture.completed
                                .targetProofOrThrow()
                                .copyBuilder()
                                .targetHistory(new History(Bytes.EMPTY, Bytes.wrap("other-vk")))
                                .build())
                        .build(),
                fixture.completed
                        .copyBuilder()
                        .keyProvenance(HintsKeyProvenance.newBuilder()
                                .targetHintsConstructionId(999)
                                .build())
                        .build());
        for (final var mismatch : mismatches) {
            assertThrows(IllegalArgumentException.class, () -> fixture.reconcile(mismatch));
        }
    }

    @Test
    void completedBootstrapSchemeRemainsUnreadyUntilHistoryCompletes() {
        final var fixture = new Fixture();
        when(fixture.rosters.phase()).thenReturn(ActiveRosters.Phase.BOOTSTRAP);
        when(fixture.rosters.targetRoster()).thenReturn(Roster.DEFAULT);
        when(fixture.store.getOrCreateConstruction(fixture.rosters, Instant.EPOCH, fixture.config))
                .thenReturn(ACTIVE);

        fixture.reconcile(
                HistoryProofConstruction.newBuilder().targetRosterHash(HASH).build());

        assertFalse(fixture.context.isReady());
    }

    private static final class Fixture {
        private final ActiveRosters rosters = mock(ActiveRosters.class);
        private final WritableHintsStore store = mock(WritableHintsStore.class);
        private final HintsContext context;
        private final HintsServiceImpl service;
        private final TssConfig config = HederaTestConfigBuilder.createConfig().getConfigData(TssConfig.class);
        private final HistoryProofConstruction completed;

        private Fixture() {
            final var library = mock(HintsLibrary.class);
            context = new HintsContext(library, HederaTestConfigBuilder::createConfig, mock(HintsSigningMetrics.class));
            final var component = mock(HintsServiceComponent.class);
            when(component.signingContext()).thenReturn(context);
            when(component.controllers()).thenReturn(mock(HintsControllers.class));
            when(rosters.phase()).thenReturn(ActiveRosters.Phase.HANDOFF);
            when(store.getActiveConstruction()).thenReturn(ACTIVE);
            final var crs = CRSState.newBuilder()
                    .stage(COMPLETED)
                    .ceremonyId(1)
                    .numParties(16)
                    .crs(Bytes.wrap("crs"))
                    .build();
            when(store.getCrsState()).thenReturn(crs);
            when(store.getCrsStateFor(ACTIVE)).thenReturn(crs);
            final var fullWeights = new TreeMap<Long, Long>();
            LongStream.range(0, 10).forEach(id -> fullWeights.put(id, 1L));
            completed = HintsGenerationTestUtils.historyFor(ACTIVE, fullWeights);
            service = new HintsServiceImpl(component, library);
        }

        private void reconcile(final HistoryProofConstruction history) {
            service.reconcile(rosters, store, Instant.EPOCH, Instant.EPOCH, config, false, history);
        }
    }
}
