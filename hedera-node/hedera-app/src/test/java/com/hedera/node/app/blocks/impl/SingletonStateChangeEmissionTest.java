// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCKS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_STREAM_INFO;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SingletonStateChangeEmissionTest {
    private static final int BLOCKS = STATE_ID_BLOCKS.protoOrdinal();
    private static final BlockInfo INITIAL = BlockInfo.newBuilder()
            .lastBlockNumber(10)
            .blockHashes(Bytes.wrap("h".repeat(200)))
            .build();
    private static final BlockInfo UPDATED =
            INITIAL.copyBuilder().lastBlockNumber(11).build();
    private final BoundaryStateChangeListener listener = new BoundaryStateChangeListener(
            new StoreMetricsServiceImpl(new NoOpMetrics()), HederaTestConfigBuilder::createConfig);

    static Stream<Arguments> outputModes() {
        return Stream.of(
                Arguments.of(true, true, 64, true),
                Arguments.of(true, false, 64, false),
                Arguments.of(true, true, 4096, false),
                Arguments.of(false, true, 64, false));
    }

    @ParameterizedTest
    @MethodSource("outputModes")
    void emittedSingletonsRetainOnlyTheSelectedPayloadWithoutChangingWireBytes(
            final boolean enabled, final boolean allowPartial, final int minSaving, final boolean partial) {
        listener.beginBlock(enabled, minSaving);
        assertThat(UPDATED.$copyBuilderOrigin()).isSameAs(INITIAL);
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        final var full =
                SingletonUpdateChange.newBuilder().blockInfoValue(UPDATED).build();
        final var expectedUpdate =
                enabled && allowPartial ? StateChangeDeltas.encodeSingleton(BLOCKS, INITIAL, full, minSaving) : full;
        final var expected = StateChange.newBuilder()
                .stateId(BLOCKS)
                .singletonUpdate(expectedUpdate)
                .build();

        final var changes = listener.allStateChanges(allowPartial);
        final var emitted = changes.getFirst();
        final var update = emitted.singletonUpdateOrThrow();
        assertThat(update.partial()).isEqualTo(partial);
        assertThat(com.hedera.pbj.runtime.CopyBuilderTracked.untracked(emitted)).isSameAs(emitted);
        assertThat(com.hedera.pbj.runtime.CopyBuilderTracked.untracked(update)).isSameAs(update);
        assertThat(update.blockInfoValueOrThrow().$copyBuilderOrigin()).isNull();
        assertThat(StateChange.PROTOBUF.toBytes(emitted)).isEqualTo(StateChange.PROTOBUF.toBytes(expected));
        assertThat(StateChangeDeltas.applySingleton(BLOCKS, INITIAL, update)).isEqualTo(UPDATED);
        assertThat(listener.allStateChanges(allowPartial)).isEqualTo(changes);
        // Diff selection must not strip or modify the handler's tracked input.
        assertThat(UPDATED.$copyBuilderOrigin()).isSameAs(INITIAL);
        listener.stateChangesEmitted(changes);
        assertThat(listener.allStateChanges()).isEmpty();
    }

    @Test
    void emissionFollowedByRevertedFieldUsesFreshCallbackBaseline() {
        listener.beginBlock(true);
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        final var first = listener.allStateChanges();
        final var firstUpdate = first.getFirst().singletonUpdateOrThrow();
        assertThat(firstUpdate.partial()).isTrue();
        Object replay = StateChangeDeltas.applySingleton(BLOCKS, INITIAL, firstUpdate);
        listener.stateChangesEmitted(first);
        assertThat(listener.allStateChanges()).isEmpty();

        listener.singletonUpdateChange(BLOCKS, UPDATED, INITIAL);
        final var second = listener.allStateChanges();
        final var secondUpdate = second.getFirst().singletonUpdateOrThrow();
        assertThat(secondUpdate.partial()).isTrue();
        replay = StateChangeDeltas.applySingleton(BLOCKS, replay, secondUpdate);
        assertThat(replay).isEqualTo(INITIAL);
        listener.stateChangesEmitted(second);
        assertThat(listener.allStateChanges()).isEmpty();
    }

    @Test
    void inspectionDoesNotDrainOrAdvanceTheBaseline() {
        listener.beginBlock(true);
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        final var inspected = listener.allStateChanges();
        assertThat(listener.allStateChanges()).isEqualTo(inspected);
        listener.singletonUpdateChange(BLOCKS, UPDATED, INITIAL);
        final var update = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(update.blockInfoValueOrThrow()).isEqualTo(BlockInfo.DEFAULT);
        assertThat(StateChangeDeltas.applySingleton(BLOCKS, INITIAL, update)).isEqualTo(INITIAL);
    }

    @Test
    void transactionOwnedSingletonDoesNotDisturbBoundaryValueAndBaseline() {
        listener.beginBlock(true);
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        final var initialConstruction = ClprEndpointManifestConstruction.newBuilder()
                .constructionId(1)
                .targetNodeIds(LongStream.range(0, 100).boxed().toList())
                .build();
        final var nextConstruction =
                initialConstruction.copyBuilder().graceExtensionsUsed(1).build();
        listener.singletonUpdateChange(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, initialConstruction, nextConstruction);
        assertThat(listener.requiresPreviousValue(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID))
                .isFalse();
        final var pending = listener.allStateChanges();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().stateId()).isEqualTo(BLOCKS);
        assertThat(StateChangeDeltas.applySingleton(
                        BLOCKS, INITIAL, pending.getFirst().singletonUpdateOrThrow()))
                .isEqualTo(UPDATED);

        final var latest = UPDATED.copyBuilder().migrationRecordsStreamed(true).build();
        listener.singletonUpdateChange(BLOCKS, UPDATED, latest);
        final var finalUpdate = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(finalUpdate.partial()).isTrue();
        assertThat(StateChangeDeltas.applySingleton(BLOCKS, INITIAL, finalUpdate))
                .isEqualTo(latest);
    }

    @Test
    void unrelatedUpdateSurvivesAnImmediateClprUpdateWithoutALaterWrite() {
        listener.beginBlock(true);
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        listener.singletonUpdateChange(
                ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, ClprEndpointManifestConstruction.DEFAULT);
        final var finalChanges = listener.allStateChanges();
        assertThat(finalChanges).hasSize(1);
        assertThat(StateChangeDeltas.applySingleton(
                        BLOCKS, INITIAL, finalChanges.getFirst().singletonUpdateOrThrow()))
                .isEqualTo(UPDATED);
    }

    @Test
    void pendingBeforeBlockStartStaysFullUntilPublished() {
        listener.singletonUpdateChange(BLOCKS, INITIAL, UPDATED);
        listener.beginBlock(true);
        final var latest = UPDATED.copyBuilder().migrationRecordsStreamed(true).build();
        listener.singletonUpdateChange(BLOCKS, UPDATED, latest);
        final var changes = listener.allStateChanges();
        final var update = changes.getFirst().singletonUpdateOrThrow();
        assertThat(update.partial()).isFalse();
        assertThat(StateChangeDeltas.applySingleton(BLOCKS, INITIAL, update)).isEqualTo(latest);
        listener.stateChangesEmitted(changes);

        listener.singletonUpdateChange(BLOCKS, latest, INITIAL);
        final var next = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(next.partial()).isTrue();
        assertThat(StateChangeDeltas.applySingleton(BLOCKS, latest, next)).isEqualTo(INITIAL);
    }

    @Test
    void fullCheckpointDrainsAndEstablishesTheNextCallbackBaseline() {
        listener.beginBlock(true);
        final int stateId = STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
        final var initial = BlockStreamInfo.newBuilder()
                .blockNumber(10)
                .trailingBlockHashes(Bytes.wrap("h".repeat(200)))
                .build();
        final var next = initial.copyBuilder().blockNumber(11).build();
        listener.singletonUpdateChange(stateId, initial, next);
        assertThat(listener.allStateChanges(true)
                        .getFirst()
                        .singletonUpdateOrThrow()
                        .partial())
                .isTrue();
        final var checkpoint = listener.allStateChanges(false);
        assertThat(checkpoint.getFirst().singletonUpdateOrThrow().partial()).isFalse();
        assertThat(StateChangeDeltas.applySingleton(
                        stateId, initial, checkpoint.getFirst().singletonUpdateOrThrow()))
                .isEqualTo(next);
        listener.stateChangesEmitted(checkpoint);
        assertThat(listener.allStateChanges()).isEmpty();

        listener.singletonUpdateChange(stateId, next, initial);
        final var update = listener.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(StateChangeDeltas.applySingleton(stateId, next, update)).isEqualTo(initial);
    }
}
