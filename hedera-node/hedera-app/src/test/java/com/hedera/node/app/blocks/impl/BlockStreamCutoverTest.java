// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_LABEL;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockStreamCutoverTest {
    private static final Bytes WRAPPED_HASH = Bytes.wrap(new byte[HASH_SIZE]);
    private static final Bytes HASH_A = Bytes.fromHex("aa".repeat(HASH_SIZE));
    private static final Bytes HASH_B = Bytes.fromHex("bb".repeat(HASH_SIZE));
    private static final Bytes HASH_C = Bytes.fromHex("cc".repeat(HASH_SIZE));
    private static final Bytes HASH_D = Bytes.fromHex("dd".repeat(HASH_SIZE));

    @TempDir
    Path tempDir;

    @Test
    void computesCutoverBlockStreamInfoFromFinalRecordStreamState() {
        final var stateHash = Bytes.fromHex("ef".repeat(HASH_SIZE));
        final var previewBlockStreamInfo = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(stateHash)
                .build();
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();

        final var cutoverBlockStreamInfo =
                BlockStreamCutover.blockStreamInfoFrom(blockInfo, runningHashes, previewBlockStreamInfo);

        assertEquals(blockInfo.lastBlockNumber(), cutoverBlockStreamInfo.blockNumber());
        assertEquals(blockInfo.firstConsTimeOfCurrentBlock(), cutoverBlockStreamInfo.blockTime());
        assertEquals(blockInfo.lastUsedConsTime(), cutoverBlockStreamInfo.blockEndTime());
        assertEquals(blockInfo.lastIntervalProcessTime(), cutoverBlockStreamInfo.lastIntervalProcessTime());
        assertEquals(blockInfo.consTimeOfLastHandledTxn(), cutoverBlockStreamInfo.lastHandleTime());
        assertEquals(stateHash, cutoverBlockStreamInfo.startOfBlockStateHash());
        assertEquals(
                blockInfo.wrappedIntermediatePreviousBlockRootHashes(),
                cutoverBlockStreamInfo.intermediatePreviousBlockRootHashes());
        assertEquals(
                blockInfo.wrappedIntermediateBlockRootsLeafCount(),
                cutoverBlockStreamInfo.intermediateBlockRootsLeafCount());
        assertEquals(
                Bytes.wrap(blockInfo.blockHashes().toByteArray(), 0, HASH_SIZE),
                cutoverBlockStreamInfo.trailingBlockHashes());
        assertEquals(
                BlockStreamCutover.trailingOutputHashesFrom(runningHashes),
                cutoverBlockStreamInfo.trailingOutputHashes());
        assertEquals(HASH_OF_ZERO, cutoverBlockStreamInfo.inputTreeRootHash());
        assertEquals(HASH_OF_ZERO, cutoverBlockStreamInfo.consensusHeaderRootHash());
        assertEquals(HASH_OF_ZERO, cutoverBlockStreamInfo.traceDataRootHash());
        assertEquals(0, cutoverBlockStreamInfo.numPrecedingStateChangesItems());
        assertEquals(List.of(), cutoverBlockStreamInfo.rightmostPrecedingStateChangesTreeHashes());
    }

    @Test
    void computesTrailingOutputHashesInRecordRunningHashOrder() {
        final var outputBytes = BlockStreamCutover.trailingOutputHashesFrom(validRunningHashes())
                .toByteArray();
        final var expectedOutput = new byte[HASH_SIZE * 4];
        HASH_D.getBytes(0, expectedOutput, 0, HASH_SIZE);
        HASH_C.getBytes(0, expectedOutput, HASH_SIZE, HASH_SIZE);
        HASH_B.getBytes(0, expectedOutput, HASH_SIZE * 2, HASH_SIZE);
        HASH_A.getBytes(0, expectedOutput, HASH_SIZE * 3, HASH_SIZE);

        assertEquals(Bytes.wrap(expectedOutput), Bytes.wrap(outputBytes));
    }

    @Test
    void trimsFinalRecordBlockHashFromTrailingBlockHashes() {
        final var threeHashes = new byte[HASH_SIZE * 3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < HASH_SIZE; j++) {
                threeHashes[i * HASH_SIZE + j] = (byte) (i + 1);
            }
        }
        final var blockInfo = validBlockInfo()
                .copyBuilder()
                .blockHashes(Bytes.wrap(threeHashes))
                .build();

        assertEquals(Bytes.wrap(threeHashes, 0, HASH_SIZE * 2), BlockStreamCutover.trailingBlockHashesFrom(blockInfo));
    }

    @Test
    void rejectsMissingRecordBlockHash() {
        final var blockInfo = validBlockInfo()
                .copyBuilder()
                .blockHashes(Bytes.wrap(new byte[HASH_SIZE - 1]))
                .build();

        final var ex =
                assertThrows(IllegalStateException.class, () -> BlockStreamCutover.trailingBlockHashesFrom(blockInfo));
        assertTrue(ex.getMessage().contains("at least one record block hash"));
    }

    @Test
    void effectiveStartupInfoUsesPersistedInfoWhenCutoverDisabled() {
        final var persistedBlockStreamInfo =
                BlockStreamInfo.newBuilder().blockNumber(12).build();
        final var state = stateWith(validBlockInfo(), validRunningHashes(), persistedBlockStreamInfo);

        final var effectiveInfo = BlockStreamCutover.effectiveStartupBlockStreamInfoFrom(state, false);

        assertFalse(effectiveInfo.previewingCutover());
        assertEquals(persistedBlockStreamInfo, effectiveInfo.blockStreamInfo());
    }

    @Test
    void effectiveStartupInfoPreviewsPendingCutover() {
        final var persistedBlockStreamInfo = BlockStreamInfo.newBuilder()
                .blockNumber(12)
                .startOfBlockStateHash(Bytes.fromHex("ef".repeat(HASH_SIZE)))
                .build();
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var state = stateWith(blockInfo, runningHashes, persistedBlockStreamInfo);

        final var effectiveInfo = BlockStreamCutover.effectiveStartupBlockStreamInfoFrom(state, true);

        assertTrue(effectiveInfo.previewingCutover());
        assertEquals(
                BlockStreamCutover.blockStreamInfoFrom(blockInfo, runningHashes, persistedBlockStreamInfo),
                effectiveInfo.blockStreamInfo());
    }

    @Test
    void effectiveStartupInfoUsesPersistedInfoWhenCutoverAlreadyApplied() {
        final var persistedBlockStreamInfo =
                BlockStreamInfo.newBuilder().blockNumber(100).build();
        final var blockInfo =
                validBlockInfo().copyBuilder().previewStreamOverwritten(true).build();
        final var state = stateWith(blockInfo, validRunningHashes(), persistedBlockStreamInfo);

        final var effectiveInfo = BlockStreamCutover.effectiveStartupBlockStreamInfoFrom(state, true);

        assertFalse(effectiveInfo.previewingCutover());
        assertEquals(persistedBlockStreamInfo, effectiveInfo.blockStreamInfo());
    }

    @Test
    void deletesPreviewBlockFiles() throws IOException {
        final var blockDir = tempDir.resolve("blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000042");
        Files.createDirectories(subdir);
        Files.createFile(subdir.resolve("block-42.blk.gz"));
        Files.createFile(subdir.resolve("block-42.mf"));
        Files.createFile(subdir.resolve("pending.pnd.gz"));
        Files.createFile(subdir.resolve("proof.pnd.json"));
        Files.createFile(subdir.resolve("readme.txt"));

        BlockStreamCutover.deletePreviewBlockFiles(configWithBlockDir(blockDir));

        assertFalse(Files.exists(subdir.resolve("block-42.blk.gz")));
        assertFalse(Files.exists(subdir.resolve("block-42.mf")));
        assertFalse(Files.exists(subdir.resolve("pending.pnd.gz")));
        assertFalse(Files.exists(subdir.resolve("proof.pnd.json")));
        assertTrue(Files.exists(subdir.resolve("readme.txt")));
    }

    @Test
    void toleratesMissingBlockDirectory() {
        assertDoesNotThrow(() ->
                BlockStreamCutover.deletePreviewBlockFiles(configWithBlockDir(tempDir.resolve("nonexistent-blocks"))));
    }

    @Test
    void ignoresFilesTooDeeplyNested() throws IOException {
        final var blockDir = tempDir.resolve("deep-blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000001");
        Files.createDirectories(subdir);
        final var deepDir = subdir.resolve("nested");
        Files.createDirectories(deepDir);
        Files.createFile(deepDir.resolve("block-deep.blk.gz"));

        BlockStreamCutover.deletePreviewBlockFiles(configWithBlockDir(blockDir));

        assertTrue(Files.exists(deepDir.resolve("block-deep.blk.gz")));
    }

    private static BlockInfo validBlockInfo() {
        final var twoHashes = new byte[HASH_SIZE * 2];
        return BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.wrap(twoHashes))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .previewStreamOverwritten(false)
                .build();
    }

    private static RunningHashes validRunningHashes() {
        return new RunningHashes(HASH_A, HASH_B, HASH_C, HASH_D);
    }

    private static com.swirlds.config.api.Configuration configWithBlockDir(final Path blockDir) {
        return HederaTestConfigBuilder.create()
                .withValue("blockStream.blockFileDir", blockDir.toString())
                .getOrCreateConfig();
    }

    private static State stateWith(
            final BlockInfo blockInfo, final RunningHashes runningHashes, final BlockStreamInfo blockStreamInfo) {
        final var state = mock(State.class);
        given(state.getReadableStates(BlockRecordService.NAME))
                .willReturn(MapReadableStates.builder()
                        .state(new FunctionReadableSingletonState<>(
                                BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, () -> blockInfo))
                        .state(new FunctionReadableSingletonState<>(
                                RUNNING_HASHES_STATE_ID, RUNNING_HASHES_STATE_LABEL, () -> runningHashes))
                        .build());
        given(state.getReadableStates(BlockStreamService.NAME))
                .willReturn(MapReadableStates.builder()
                        .state(new FunctionReadableSingletonState<>(
                                BLOCK_STREAM_INFO_STATE_ID, BLOCK_STREAM_INFO_STATE_LABEL, () -> blockStreamInfo))
                        .build());
        return state;
    }
}
