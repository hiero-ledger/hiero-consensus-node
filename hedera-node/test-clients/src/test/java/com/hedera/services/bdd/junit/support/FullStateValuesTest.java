// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullStateValuesTest {
    private static final int ACCOUNTS = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final AccountID KEY = AccountID.newBuilder().accountNum(2).build();
    static final Account FIRST =
            Account.newBuilder().memo("m".repeat(100)).tinybarBalance(1).build();
    static final Account SECOND = FIRST.copyBuilder().tinybarBalance(2).build();
    static final Account THIRD = SECOND.copyBuilder().tinybarBalance(3).build();

    @TempDir
    Path dir;

    @Test
    void expandsPartialsAfterReplayingEarlierBlocksFromDisk() throws Exception {
        final var genesis = written(block(0, full(FIRST)));
        final var unchanged = written(block(1, null));
        final var partial = written(block(2, partial(FIRST, SECOND)));
        final var later = written(block(3, partial(SECOND, THIRD)));
        final var subject = new FullStateValues();

        assertThat(subject.expand(dir, genesis)).isSameAs(genesis);
        assertThat(subject.expand(dir, unchanged)).isSameAs(unchanged);
        assertThat(accountIn(subject.expand(dir, partial))).isEqualTo(full(SECOND));
        assertThat(accountIn(subject.expand(dir, later))).isEqualTo(full(THIRD));
        // A block it has already passed cannot safely use the current baseline.
        assertThatThrownBy(() -> subject.expand(dir, partial))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Out-of-order block #2; expected #4");
    }

    @Test
    void missingBaselineFailsExplicitlyAndStopsLaterExpansion() throws Exception {
        // Without block 0 on disk there is no baseline for the partial in block 1
        final var partial = written(block(1, partial(FIRST, SECOND)));
        final var later = written(block(2, partial(SECOND, THIRD)));
        final var subject = new FullStateValues();

        assertThatThrownBy(() -> subject.expand(dir, partial))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Partial update requires a prior value");
        assertThatThrownBy(() -> subject.expand(dir, later))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Partial update requires a prior value");
    }

    @Test
    void aGapInCompletedBlocksFailsInsteadOfReturningASparseValue() throws Exception {
        written(block(0, full(FIRST)));
        final var subject = new FullStateValues();
        subject.expand(dir, written(block(1, partial(FIRST, SECOND))));
        final var later = written(block(3, partial(SECOND, THIRD)));
        assertThatThrownBy(() -> subject.expand(dir, later))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Blocks #2 to #2 are incomplete in " + dir);
    }

    @Test
    void fullValueListenersReceiveAnErrorWhileRawListenersKeepTheOriginalBlock() {
        final var sparse = block(1, partial(FIRST, SECOND));
        final var delivered = new java.util.ArrayList<Block>();
        final var errors = new java.util.ArrayList<Throwable>();
        final var listener = new StreamFileAlterationListener();
        listener.subscribe(new StreamDataListener() {
            @Override
            public boolean wantsFullStateValues() {
                return true;
            }

            @Override
            public void onNewBlock(final Block block) {
                throw new AssertionError("Sparse value delivered as full");
            }

            @Override
            public void onBlockError(final Throwable error) {
                errors.add(error);
            }
        });
        listener.subscribe(new StreamDataListener() {
            @Override
            public void onNewBlock(final Block block) {
                delivered.add(block);
            }
        });
        listener.exposeBlock(dir, sparse);
        assertThat(delivered).containsExactly(sparse);
        assertThat(errors).singleElement().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unhandledExpansionErrorsFailTheListener() {
        final var listener = new StreamFileAlterationListener();
        listener.subscribe(new StreamDataListener() {
            @Override
            public boolean wantsFullStateValues() {
                return true;
            }
        });
        assertThatThrownBy(() -> listener.exposeBlock(dir, block(1, partial(FIRST, SECOND))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot reconstruct full block state values");
    }

    @Test
    void replaysArchivedPrefixBeforeActiveDeltasEvenWhenCutoverResetsBlockNumbers() throws Exception {
        final var active = Files.createDirectories(dir.resolve("blockStreams/block-0.0.3"));
        final var archive = Files.createDirectories(dir.resolve("blockStreams-preview-archive/block-0.0.3"));
        written(archive, block(0, full(FIRST)));
        written(archive, block(1, null));
        // The preview stream ran ahead of the record stream before cutover reset numbering.
        written(archive, block(2, full(SECOND)));
        final var firstActive = written(active, block(1, partial(SECOND, THIRD)));
        final var subject = new FullStateValues();
        assertThat(accountIn(subject.expand(active, firstActive))).isEqualTo(full(THIRD));
        assertThat(accountIn(subject.expand(active, written(active, block(2, partial(THIRD, FIRST))))))
                .isEqualTo(full(FIRST));
    }

    @Test
    void archiveDoesNotHideAGapInTheActiveHistory() throws Exception {
        final var active = Files.createDirectories(dir.resolve("blockStreams/block-0.0.3"));
        final var archive = Files.createDirectories(dir.resolve("blockStreams-preview-archive/block-0.0.3"));
        written(archive, block(0, full(FIRST)));
        written(active, block(1, null));
        final var later = written(active, block(3, partial(FIRST, SECOND)));
        assertThatThrownBy(() -> new FullStateValues().expand(active, later))
                .hasRootCauseMessage("Blocks #1 to #2 are incomplete in " + active);
    }

    @Test
    void gapInArchiveFailsBeforeExpandingTheActiveSuffix() throws Exception {
        final var active = Files.createDirectories(dir.resolve("blockStreams/block-0.0.3"));
        final var archive = Files.createDirectories(dir.resolve("blockStreams-preview-archive/block-0.0.3"));
        written(archive, block(0, full(FIRST)));
        written(archive, block(2, full(SECOND)));
        final var partial = written(active, block(3, partial(SECOND, THIRD)));
        assertThatThrownBy(() -> new FullStateValues().expand(active, partial))
                .hasRootCauseMessage("Blocks #0 to #2 are incomplete in " + archive);
    }

    private Block written(final Block block) throws Exception {
        return written(dir, block);
    }

    private Block written(final Path destination, final Block block) throws Exception {
        final var name = String.format(
                "%036d", block.items().getFirst().blockHeaderOrThrow().number());
        Files.write(
                destination.resolve(name + ".blk"),
                Block.PROTOBUF.toBytes(block).toByteArray());
        Files.createFile(destination.resolve(name + ".mf"));
        return block;
    }

    static Block block(final long number, final MapUpdateChange update) {
        final var header = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(number))
                .build();
        if (update == null) {
            return Block.newBuilder().items(header).build();
        }
        final var changes = BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .stateChanges(StateChange.newBuilder()
                                .stateId(ACCOUNTS)
                                .mapUpdate(update)
                                .build()))
                .build();
        return Block.newBuilder().items(header, changes).build();
    }

    static MapUpdateChange full(final Account account) {
        return MapUpdateChange.newBuilder()
                .key(MapChangeKey.newBuilder().accountIdKey(KEY))
                .value(MapChangeValue.newBuilder().accountValue(account))
                .build();
    }

    static MapUpdateChange partial(final Account prior, final Account next) {
        final var update = StateChangeDeltas.encodeMap(ACCOUNTS, prior, full(next));
        assertThat(update.partial()).isTrue();
        return update;
    }

    static MapUpdateChange accountIn(final Block block) {
        return block.items()
                .getLast()
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .mapUpdateOrThrow();
    }
}
