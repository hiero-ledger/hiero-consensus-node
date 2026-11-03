// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.pbj.runtime.CopyBuilderTracked;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ImmediateSingletonDeltaEmissionTest {
    static Stream<Arguments> singletonUpdates() {
        final var configuration = ClprLedgerConfiguration.newBuilder()
                .chainId("chain".repeat(100))
                .protocolVersion(1)
                .build();
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(1)
                .serviceAddress(Bytes.wrap(new byte[200]))
                .build();
        final var construction = ClprEndpointManifestConstruction.newBuilder()
                .constructionId(1)
                .targetNodeIds(LongStream.range(0, 100).boxed().toList())
                .build();
        return Stream.of(
                Arguments.of(
                        LEDGER_CONFIGURATION_STATE_ID,
                        configuration,
                        configuration.copyBuilder().protocolVersion(2).build()),
                Arguments.of(
                        ENDPOINT_MANIFEST_STATE_ID,
                        manifest,
                        manifest.copyBuilder().version(2).build()),
                Arguments.of(
                        ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID,
                        construction,
                        construction.copyBuilder().graceExtensionsUsed(1).build()));
    }

    @ParameterizedTest
    @MethodSource("singletonUpdates")
    void immediateUpdatesReplayInSequenceWithoutBoundaryDuplicates(
            final int stateId, final Object prior, final Object next) {
        final var immediate = new ImmediateStateChangeListener();
        final var boundary = new BoundaryStateChangeListener((type, capacity) -> null, () -> null);
        immediate.beginBlock(true);
        boundary.beginBlock(true);

        immediate.singletonUpdateChange(stateId, null, prior);
        immediate.singletonUpdateChange(stateId, prior, next);
        immediate.singletonUpdateChange(stateId, next, prior);
        boundary.singletonUpdateChange(stateId, null, prior);
        boundary.singletonUpdateChange(stateId, prior, next);
        assertThat(boundary.allStateChanges()).isEmpty();
        assertThat(boundary.requiresPreviousValue(stateId)).isFalse();
        assertThat(immediate.getKvStateChanges()).isEmpty();
        assertThat(immediate.getSingletonStateChanges()).hasSize(3);

        Object replay = null;
        for (int i = 0; i < 3; i++) {
            final var update = immediate.getSingletonStateChanges().get(i).singletonUpdateOrThrow();
            assertThat(update.partial()).isEqualTo(i != 0);
            final Object wireValue = update.newValue().value();
            assertThat(CopyBuilderTracked.untracked(wireValue)).isSameAs(wireValue);
            replay = StateChangeDeltas.applySingleton(stateId, replay, update);
            assertThat(replay).isEqualTo(i == 1 ? next : prior);
        }
        immediate.resetSingletonStateChanges();
        assertThat(immediate.getStateChanges()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("singletonUpdates")
    void disabledOrTooSmallSavingsKeepTheFullValue(final int stateId, final Object prior, final Object next) {
        for (final boolean enabled : new boolean[] {false, true}) {
            final var immediate = new ImmediateStateChangeListener();
            immediate.beginBlock(enabled, 100_000);
            immediate.singletonUpdateChange(stateId, prior, next);
            final var update = immediate.getSingletonStateChanges().getFirst().singletonUpdateOrThrow();
            assertThat(update.partial()).isFalse();
            assertThat(StateChangeDeltas.applySingleton(stateId, prior, update)).isEqualTo(next);
        }
    }
}
