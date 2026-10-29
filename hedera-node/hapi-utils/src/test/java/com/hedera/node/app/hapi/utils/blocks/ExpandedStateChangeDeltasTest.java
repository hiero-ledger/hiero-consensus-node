// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpandedStateChangeDeltasTest {
    @Test
    void everyPayloadAlternativeHasAnAdapterWithoutAStateIdAllowlist() {
        for (var kind : MapChangeValue.ValueChoiceOneOfType.values()) {
            if (kind.protoOrdinal() <= 0) continue;
            var type = StateChangeDeltas.mapType(kind.protoOrdinal());
            assertThat(type).as(kind.name()).isNotNull();
            var value = type.defaultValue();
            var full = MapUpdateChange.newBuilder()
                    .key(MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT))
                    .value(new MapChangeValue(new OneOf<>(kind, type.wireValue(value))))
                    .build();
            assertThat(StateChangeDeltas.encodeMap(9999, null, full).partial()).isFalse();
            assertThat(StateChangeDeltas.applyMap(
                            9999, value, full.copyBuilder().partial(true).build()))
                    .isEqualTo(value);
        }
        for (var kind : SingletonUpdateChange.NewValueOneOfType.values()) {
            if (kind.protoOrdinal() <= 0) continue;
            var type = StateChangeDeltas.singletonType(kind.protoOrdinal());
            assertThat(type).as(kind.name()).isNotNull();
            var value = type.defaultValue();
            var full = new SingletonUpdateChange(false, List.of(), new OneOf<>(kind, type.wireValue(value)));
            assertThat(StateChangeDeltas.encodeSingleton(9999, null, full).partial())
                    .isFalse();
            var partial = new SingletonUpdateChange(true, List.of(), full.newValue());
            assertThat(StateChangeDeltas.applySingleton(9999, value, partial)).isEqualTo(value);
        }
    }

    @Test
    void translationMaintainsBaselinesAcrossBlocksAndDeletesWithoutChangingProofBytes() {
        final var before =
                Account.newBuilder().memo("m".repeat(100)).tinybarBalance(1).build();
        final var next = before.copyBuilder().tinybarBalance(2).build();
        final var full = StateChangeDeltasTest.full(before);
        final var partial = StateChangeDeltas.encodeMap(2, before, StateChangeDeltasTest.full(next));
        final var reconstructor = new StateChangeReconstructor();
        reconstructor.fullValuesForTranslation(block(full));
        final var original = block(partial);
        final var bytes = Block.PROTOBUF.toBytes(original);
        final var expanded = reconstructor
                .fullValuesForTranslation(original)
                .items()
                .getFirst()
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .mapUpdateOrThrow();
        assertThat(expanded.partial()).isFalse();
        assertThat(expanded.clearedFields()).isEmpty();
        assertThat(expanded.valueOrThrow().accountValue()).isEqualTo(next);
        assertThat(Block.PROTOBUF.toBytes(original)).isEqualTo(bytes);
        reconstructor.reconstruct(StateChanges.newBuilder()
                .stateChanges(StateChange.newBuilder()
                        .stateId(2)
                        .mapDelete(MapDeleteChange.newBuilder().key(full.key()))
                        .build())
                .build());
        assertThatThrownBy(() -> reconstructor.fullValuesForTranslation(original))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singletonTranslationCarriesReconstructedBaselinesAcrossBlocks() {
        final int blocks = StateIdentifier.STATE_ID_BLOCKS.protoOrdinal();
        final var initial = BlockInfo.newBuilder()
                .lastBlockNumber(10)
                .blockHashes(Bytes.wrap("hashes"))
                .build();
        final var full =
                SingletonUpdateChange.newBuilder().blockInfoValue(initial).build();
        final var partial = SingletonUpdateChange.newBuilder()
                .blockInfoValue(BlockInfo.newBuilder().lastBlockNumber(11))
                .partial(true)
                .build();
        final var reconstructor = new StateChangeReconstructor();
        reconstructor.fullValuesForTranslation(singletonBlock(blocks, full));
        final var original = singletonBlock(blocks, partial);
        final var originalBytes = Block.PROTOBUF.toBytes(original);

        final var expanded = reconstructor
                .fullValuesForTranslation(original)
                .items()
                .getFirst()
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .singletonUpdateOrThrow();
        assertThat(expanded.partial()).isFalse();
        assertThat(expanded.blockInfoValue())
                .isEqualTo(initial.copyBuilder().lastBlockNumber(11).build());
        final var clear = SingletonUpdateChange.newBuilder()
                .blockInfoValue(BlockInfo.DEFAULT)
                .partial(true)
                .clearedFields(List.of(3))
                .build();
        final var cleared = reconstructor
                .fullValuesForTranslation(singletonBlock(blocks, clear))
                .items()
                .getFirst()
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .singletonUpdateOrThrow();
        assertThat(cleared.blockInfoValue())
                .isEqualTo(BlockInfo.newBuilder().lastBlockNumber(11).build());
        assertThat(Block.PROTOBUF.toBytes(original)).isEqualTo(originalBytes);
    }

    private static Block singletonBlock(final int stateId, final SingletonUpdateChange update) {
        return Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .stateChanges(StateChanges.newBuilder()
                                .stateChanges(StateChange.newBuilder()
                                        .stateId(stateId)
                                        .singletonUpdate(update)
                                        .build()))
                        .build())
                .build();
    }

    @Test
    void fullAndPartialHistoriesRemainRecognizableWithoutHeaderVersion() {
        final var full = StateChangeDeltasTest.full(Account.DEFAULT);
        assertThat(StateChangeDeltas.hasPartialUpdates(block(full))).isFalse();
        assertThat(StateChangeDeltas.hasPartialUpdates(
                        block(full.copyBuilder().partial(true).build())))
                .isTrue();
        StateChangeDeltas.validateBlockDeltas(block(full));
        assertThatThrownBy(() -> StateChangeDeltas.validateBlockDeltas(
                        block(full.copyBuilder().clearedFields(5).build())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Block block(MapUpdateChange update) {
        return Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .stateChanges(StateChanges.newBuilder()
                                .stateChanges(StateChange.newBuilder()
                                        .stateId(2)
                                        .mapUpdate(update)
                                        .build()))
                        .build())
                .build();
    }
}
