// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCKS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_LEDGER_ID;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess.computeSingletonValueFromUpdates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.RemovedStateChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockStreamAccessTest {
    private static final int BLOCKS = STATE_ID_BLOCKS.protoOrdinal();
    private static final BlockInfo INITIAL = BlockInfo.newBuilder()
            .lastBlockNumber(10)
            .blockHashes(Bytes.wrap("h".repeat(200)))
            .firstConsTimeOfCurrentBlock(new Timestamp(123, 4))
            .build();

    @TempDir
    Path tempDir;

    private static final Block SAMPLE_BLOCK = Block.newBuilder()
            .items(BlockItem.newBuilder()
                    .blockHeader(BlockHeader.newBuilder().number(42L).build())
                    .build())
            .build();

    @Test
    void singletonLookupReplaysPartialsAcrossBlocksAndRetainsOmittedFields() {
        final var first = block(singleton(full(INITIAL)));
        final var second = block(
                singleton(partial(BlockInfo.newBuilder().lastBlockNumber(11).build())));
        final var third = block(singleton(
                partial(BlockInfo.newBuilder().migrationRecordsStreamed(true).build())));
        final var blocks = List.of(first, second, third);
        final var originalBytes = blocks.stream().map(Block.PROTOBUF::toBytes).toList();

        assertEquals(
                INITIAL.copyBuilder()
                        .lastBlockNumber(11)
                        .migrationRecordsStreamed(true)
                        .build(),
                computeSingletonValueFromUpdates(blocks, SingletonUpdateChange::blockInfoValue, BLOCKS));
        assertEquals(
                INITIAL.blockHashes(),
                computeSingletonValueFromUpdates(
                        blocks, update -> update.blockInfoValueOrThrow().blockHashes(), BLOCKS));
        assertEquals(originalBytes, blocks.stream().map(Block.PROTOBUF::toBytes).toList());
    }

    @Test
    void singletonLookupAppliesExplicitClears() {
        final var cleared = SingletonUpdateChange.newBuilder()
                .blockInfoValue(BlockInfo.newBuilder().lastBlockNumber(11))
                .partial(true)
                .clearedFields(List.of(3, 6))
                .build();
        final var result = computeSingletonValueFromUpdates(
                List.of(block(singleton(full(INITIAL)), singleton(cleared))),
                SingletonUpdateChange::blockInfoValue,
                BLOCKS);

        assertEquals(
                INITIAL.copyBuilder()
                        .lastBlockNumber(11)
                        .blockHashes(Bytes.EMPTY)
                        .firstConsTimeOfCurrentBlock((Timestamp) null)
                        .build(),
                result);
    }

    @Test
    void fullReplacementResetsTheBaselineForLaterPartials() {
        final var replacement = BlockInfo.newBuilder().lastBlockNumber(20).build();
        final var result = computeSingletonValueFromUpdates(
                List.of(block(
                        singleton(full(INITIAL)),
                        singleton(partial(
                                BlockInfo.newBuilder().lastBlockNumber(11).build())),
                        singleton(full(replacement)),
                        singleton(partial(
                                BlockInfo.newBuilder().lastBlockNumber(21).build())))),
                SingletonUpdateChange::blockInfoValue,
                BLOCKS);

        assertEquals(replacement.copyBuilder().lastBlockNumber(21).build(), result);
    }

    @Test
    void streamSuffixCanUseAnExplicitFullBaseline() {
        final var result = computeSingletonValueFromUpdates(
                List.of(block(singleton(
                        partial(BlockInfo.newBuilder().lastBlockNumber(11).build())))),
                update -> {
                    assertFalse(update.partial());
                    return update.blockInfoValue();
                },
                BLOCKS,
                full(INITIAL));

        assertEquals(INITIAL.copyBuilder().lastBlockNumber(11).build(), result);
        assertEquals(
                INITIAL,
                computeSingletonValueFromUpdates(
                        List.of(), SingletonUpdateChange::blockInfoValue, BLOCKS, full(INITIAL)));
        assertNull(computeSingletonValueFromUpdates(List.of(), SingletonUpdateChange::blockInfoValue, BLOCKS));
    }

    @Test
    void partialWithoutABaselineFailsAndAPartialCannotBeUsedAsABaseline() {
        final var update = partial(BlockInfo.newBuilder().lastBlockNumber(11).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> computeSingletonValueFromUpdates(
                        List.of(block(singleton(update))), SingletonUpdateChange::blockInfoValue, BLOCKS));
        assertThrows(
                IllegalArgumentException.class,
                () -> computeSingletonValueFromUpdates(
                        List.of(), SingletonUpdateChange::blockInfoValue, BLOCKS, update));
    }

    @Test
    void stateRemovalInvalidatesTheBaselineAndFullRecreationStartsFresh() {
        final var removal = StateChange.newBuilder()
                .stateId(BLOCKS)
                .stateRemove(RemovedStateChange.DEFAULT)
                .build();
        assertNull(computeSingletonValueFromUpdates(
                List.of(block(singleton(full(INITIAL)), removal)), SingletonUpdateChange::blockInfoValue, BLOCKS));
        assertThrows(
                IllegalArgumentException.class,
                () -> computeSingletonValueFromUpdates(
                        List.of(block(
                                removal,
                                singleton(partial(BlockInfo.newBuilder()
                                        .lastBlockNumber(11)
                                        .build())))),
                        SingletonUpdateChange::blockInfoValue,
                        BLOCKS,
                        full(INITIAL)));

        final var recreated = BlockInfo.newBuilder().lastBlockNumber(20).build();
        assertEquals(
                recreated,
                computeSingletonValueFromUpdates(
                        List.of(block(removal, singleton(full(recreated)))),
                        SingletonUpdateChange::blockInfoValue,
                        BLOCKS,
                        full(INITIAL)));
    }

    @Test
    void wrapperSingletonsPreserveTheExistingWireValueExtractionApi() {
        final int ledgerId = STATE_ID_LEDGER_ID.protoOrdinal();
        final var initial =
                SingletonUpdateChange.newBuilder().bytesValue(Bytes.wrap("old")).build();
        final var clear = SingletonUpdateChange.newBuilder()
                .bytesValue(Bytes.EMPTY)
                .partial(true)
                .clearedFields(List.of(1))
                .build();
        final var changes = List.of(block(
                StateChange.newBuilder()
                        .stateId(ledgerId)
                        .singletonUpdate(initial)
                        .build(),
                StateChange.newBuilder()
                        .stateId(ledgerId)
                        .singletonUpdate(clear)
                        .build()));

        assertEquals(
                Bytes.EMPTY, computeSingletonValueFromUpdates(changes, SingletonUpdateChange::bytesValue, ledgerId));
    }

    private static SingletonUpdateChange full(final BlockInfo value) {
        return SingletonUpdateChange.newBuilder().blockInfoValue(value).build();
    }

    private static SingletonUpdateChange partial(final BlockInfo value) {
        return SingletonUpdateChange.newBuilder()
                .blockInfoValue(value)
                .partial(true)
                .build();
    }

    private static StateChange singleton(final SingletonUpdateChange update) {
        return StateChange.newBuilder().stateId(BLOCKS).singletonUpdate(update).build();
    }

    private static Block block(final StateChange... changes) {
        return Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .stateChanges(StateChanges.newBuilder().stateChanges(changes))
                        .build())
                .build();
    }

    @Test
    void blockFrom_withPlainBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var blockFile = tempDir.resolve("000000000000000000042.blk");
        Files.write(blockFile, blockBytes);

        final var result = BlockStreamAccess.blockFrom(blockFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withGzippedBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var gzippedFile = tempDir.resolve("000000000000000000042.blk.gz");
        try (var baos = new ByteArrayOutputStream();
                var gzip = new GZIPOutputStream(baos)) {
            gzip.write(blockBytes);
            gzip.finish();
            Files.write(gzippedFile, baos.toByteArray());
        }

        final var result = BlockStreamAccess.blockFrom(gzippedFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withInvalidBytes_throwsRuntimeException() throws IOException {
        final var blockFile = tempDir.resolve("000000000000000000001.blk");
        Files.write(blockFile, new byte[] {0x06});

        assertThrows(RuntimeException.class, () -> BlockStreamAccess.blockFrom(blockFile));
    }

    @Test
    void readBlocksIgnoringMarkersAndReadErrors_skipsUnreadableBlocks() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var readableBlockFile = tempDir.resolve("000000000000000000041.blk");
        final var unreadableGzippedBlockFile = tempDir.resolve("000000000000000000042.blk.gz");
        Files.write(readableBlockFile, blockBytes);
        Files.write(unreadableGzippedBlockFile, new byte[0]);

        assertThrows(
                RuntimeException.class, () -> BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(tempDir));

        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkersAndReadErrors(tempDir);

        assertEquals(List.of(SAMPLE_BLOCK), blocks);
    }
}
