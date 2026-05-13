// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockStreamCutoverTest {
    private static final Bytes WRAPPED_HASH = Bytes.wrap(new byte[HASH_SIZE]);
    private static final Bytes HASH_A = Bytes.fromHex("aa".repeat(HASH_SIZE));
    private static final Bytes HASH_B = Bytes.fromHex("bb".repeat(HASH_SIZE));
    private static final Bytes HASH_C = Bytes.fromHex("cc".repeat(HASH_SIZE));
    private static final Bytes HASH_D = Bytes.fromHex("dd".repeat(HASH_SIZE));

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
}
