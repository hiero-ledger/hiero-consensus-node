// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BlockStreamInfoImpl}, the {@code streamMode == BLOCKS} source of
 * {@link com.hedera.node.app.spi.records.BlockRecordInfo}. Beyond covering each accessor, these tests assert
 * <b>full parity</b> with {@link BlockRecordInfoImpl} on {@code blockNo()} and {@code blockHashByBlockNumber()} —
 * including that the last completed block's hash (absent from the state-resident trailing hashes) is reconstructed
 * so {@code blockhash(block.number - 1)} resolves exactly as in records mode.
 */
@ExtendWith(MockitoExtension.class)
class BlockStreamInfoImplTest {
    private static final Timestamp BLOCK_TIME =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState<BlockStreamInfo> singletonState;

    /** A distinct, deterministic 48-byte (SHA-384-length) hash filled with the given byte. */
    private static Bytes hash(final int seed) {
        final var bytes = new byte[HASH_SIZE];
        java.util.Arrays.fill(bytes, (byte) seed);
        return Bytes.wrap(bytes);
    }

    private static Bytes concat(final Bytes... parts) {
        var total = 0;
        for (final var p : parts) {
            total += (int) p.length();
        }
        final var out = new byte[total];
        var off = 0;
        for (final var p : parts) {
            p.getBytes(0, out, off, (int) p.length());
            off += (int) p.length();
        }
        return Bytes.wrap(out);
    }

    /** A fully-populated {@link BlockStreamInfo} whose subtree roots let {@code reconstructLastBlockHash} run. */
    private static BlockStreamInfo info(final long blockNumber, final Bytes trailingBlockHashes) {
        return BlockStreamInfo.newBuilder()
                .blockNumber(blockNumber)
                .blockTime(BLOCK_TIME)
                .blockEndTime(BLOCK_TIME)
                .trailingBlockHashes(trailingBlockHashes)
                .trailingOutputHashes(Bytes.EMPTY)
                .intermediatePreviousBlockRootHashes(List.of())
                .intermediateBlockRootsLeafCount(0L)
                .rightmostPrecedingStateChangesTreeHashes(List.of())
                .numPrecedingStateChangesItems(0L)
                .startOfBlockStateHash(hash(11))
                .consensusHeaderRootHash(hash(12))
                .inputTreeRootHash(hash(13))
                .outputItemRootHash(hash(14))
                .traceDataRootHash(hash(15))
                .build();
    }

    @Test
    void blockNoIsBlockNumberPlusOne() {
        assertEquals(0L, new BlockStreamInfoImpl(info(-1L, Bytes.EMPTY)).blockNo(), "genesis matches records");
        assertEquals(1L, new BlockStreamInfoImpl(info(0L, Bytes.EMPTY)).blockNo());
        assertEquals(667L, new BlockStreamInfoImpl(info(666L, hash(1))).blockNo());
    }

    @Test
    void blockTimestampUsesBlockTimeWithDefaultFallback() {
        assertEquals(BLOCK_TIME, new BlockStreamInfoImpl(info(5L, hash(1))).blockTimestamp());
        final var noTime = BlockStreamInfo.newBuilder().blockNumber(5L).build();
        assertEquals(Timestamp.DEFAULT, new BlockStreamInfoImpl(noTime).blockTimestamp());
    }

    @Test
    void prngSeedIsLeftmostTrailingOutputHashOnceFourArePresent() {
        // Four or more output hashes: seed is the leftmost (n-minus-3 running hash).
        final var four = concat(hash(1), hash(2), hash(3), hash(4));
        assertEquals(
                hash(1),
                new BlockStreamInfoImpl(BlockStreamInfo.newBuilder()
                                .trailingOutputHashes(four)
                                .build())
                        .prngSeed());
        // Fewer than four: not yet available.
        final var three = concat(hash(1), hash(2), hash(3));
        assertNull(new BlockStreamInfoImpl(
                        BlockStreamInfo.newBuilder().trailingOutputHashes(three).build())
                .prngSeed());
        assertNull(new BlockStreamInfoImpl(BlockStreamInfo.newBuilder()
                        .trailingOutputHashes(Bytes.EMPTY)
                        .build())
                .prngSeed());
    }

    @Test
    void blockHashByBlockNumberIsNullAtGenesis() {
        final var subject = new BlockStreamInfoImpl(info(-1L, Bytes.EMPTY));
        assertNull(subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(-1L));
    }

    @Test
    void blockHashByBlockNumberReconstructsLastBlockAndResolvesTrailing() {
        // blockNumber = 3, trailing covers blocks 1 and 2; block 3's own hash is not in state.
        final var trailing = concat(hash(1), hash(2));
        final var blockStreamInfo = info(3L, trailing);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo);
        final var reconstructedLast = BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo);

        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(3L), "last block hash is reconstructed");
        assertEquals(hash(2), subject.blockHashByBlockNumber(2L), "previous block comes from trailing hashes");
        assertEquals(hash(1), subject.blockHashByBlockNumber(1L));
        assertNull(subject.blockHashByBlockNumber(0L), "older than the available window");
        assertNull(subject.blockHashByBlockNumber(4L), "future block");
    }

    @Test
    void blockHashByBlockNumberReconstructsTheVeryFirstBlock() {
        // blockNumber = 0: no prior trailing hashes; block 0's hash is reconstructed from genesis.
        final var blockStreamInfo = info(0L, Bytes.EMPTY);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo);
        final var reconstructedLast = BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo);

        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(1L));
        assertNull(subject.blockHashByBlockNumber(-1L));
    }

    @Test
    void extendedHashesAreStableAcrossRepeatedCalls() {
        final var subject = new BlockStreamInfoImpl(info(3L, concat(hash(1), hash(2))));
        assertEquals(subject.blockHashByBlockNumber(3L), subject.blockHashByBlockNumber(3L));
        assertEquals(subject.blockHashByBlockNumber(2L), subject.blockHashByBlockNumber(2L));
    }

    @Test
    void fromStateReadsBlockStreamInfoSingleton() {
        given(state.getReadableStates(BlockStreamService.NAME)).willReturn(readableStates);
        given(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(singletonState);
        given(singletonState.get()).willReturn(info(10L, hash(1)));

        final var subject = BlockStreamInfoImpl.from(state);

        assertEquals(11L, subject.blockNo());
        assertEquals(BLOCK_TIME, subject.blockTimestamp());
    }

    @Test
    void blockNoHasFullParityWithRecordsAcrossLifecycle() {
        for (final long lastCompleted : new long[] {-1L, 0L, 5L, 665L}) {
            final var blocks = new BlockStreamInfoImpl(info(lastCompleted, lastCompleted < 0 ? Bytes.EMPTY : hash(1)));
            final var records = new BlockRecordInfoImpl(
                    BlockInfo.newBuilder()
                            .lastBlockNumber(lastCompleted)
                            .blockHashes(Bytes.EMPTY)
                            .firstConsTimeOfCurrentBlock(BLOCK_TIME)
                            .build(),
                    RunningHashes.DEFAULT);
            assertEquals(records.blockNo(), blocks.blockNo(), "blockNo parity at lastCompleted=" + lastCompleted);
        }
    }

    @Test
    void blockHashByBlockNumberHasFullParityWithRecords() {
        // Blocks-mode view: blockNumber = 3, trailing covers blocks 1 and 2.
        final var trailing = concat(hash(1), hash(2));
        final var blockStreamInfo = info(3L, trailing);
        final var blocks = new BlockStreamInfoImpl(blockStreamInfo);
        final var lastBlockHash = BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo);

        // Equivalent records-mode view: lastBlockNumber = 3 and blockHashes include block 3's hash directly.
        final var records = new BlockRecordInfoImpl(
                BlockInfo.newBuilder()
                        .lastBlockNumber(3L)
                        .blockHashes(concat(hash(1), hash(2), lastBlockHash))
                        .firstConsTimeOfCurrentBlock(BLOCK_TIME)
                        .build(),
                RunningHashes.DEFAULT);

        for (long n = -1; n <= 4; n++) {
            assertEquals(
                    records.blockHashByBlockNumber(n),
                    blocks.blockHashByBlockNumber(n),
                    "blockHashByBlockNumber parity at block " + n);
        }
        assertEquals(records.blockNo(), blocks.blockNo());
    }
}
