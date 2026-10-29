// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification.traceability;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.*;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidecarWatcherTest {
    @TempDir
    Path dir;

    @Test
    void diskRescanRestoresTheArchivedBaselineOnEveryFreshExpansion() throws Exception {
        final var active = Files.createDirectories(dir.resolve("blockStreams/block-0.0.3"));
        final var archive = Files.createDirectories(dir.resolve("blockStreams-preview-archive/block-0.0.3"));
        final var before =
                Account.newBuilder().memo("m".repeat(200)).tinybarBalance(1).build();
        final var after = before.copyBuilder().tinybarBalance(2).build();
        final var full = MapUpdateChange.newBuilder()
                .key(MapChangeKey.newBuilder().accountIdKey(AccountID.DEFAULT))
                .value(MapChangeValue.newBuilder().accountValue(before))
                .build();
        final var next = full.copyBuilder()
                .value(MapChangeValue.newBuilder().accountValue(after))
                .build();
        final int stateId = StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal();
        final var delta = StateChangeDeltas.encodeMap(stateId, before, next);
        assertThat(delta.partial()).isTrue();
        final var baseline = block(0, stateId, full);
        final var suffix = block(1, stateId, delta);
        write(archive, 0, baseline);
        write(active, 1, suffix);
        final var watcher = new SidecarWatcher(null, dir.resolve("sidecars"), active, 0, 0);
        for (int rescan = 0; rescan < 2; rescan++) {
            final var expanded = watcher.withFullStateValues(List.of(suffix)).getFirst();
            final var update = expanded.items()
                    .getLast()
                    .stateChangesOrThrow()
                    .stateChanges()
                    .getFirst()
                    .mapUpdateOrThrow();
            assertThat(update.partial()).isFalse();
            assertThat(update.valueOrThrow().accountValue()).isEqualTo(after);
        }
    }

    private static Block block(long number, int stateId, MapUpdateChange update) {
        return Block.newBuilder()
                .items(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder().number(number))
                                .build(),
                        BlockItem.newBuilder()
                                .stateChanges(StateChanges.newBuilder()
                                        .stateChanges(StateChange.newBuilder()
                                                .stateId(stateId)
                                                .mapUpdate(update)
                                                .build()))
                                .build())
                .build();
    }

    private static void write(Path dir, long number, Block block) throws Exception {
        final var name = String.format("%036d", number);
        Files.write(dir.resolve(name + ".blk"), Block.PROTOBUF.toBytes(block).toByteArray());
        Files.createFile(dir.resolve(name + ".mf"));
    }
}
