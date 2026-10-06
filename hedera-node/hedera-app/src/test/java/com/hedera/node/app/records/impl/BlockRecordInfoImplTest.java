// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BlockRecordInfoImpl}, the {@code streamMode != BLOCKS} (legacy {@link BlockInfo}) source of
 * {@link com.hedera.node.app.spi.records.BlockRecordInfo}. These also serve as the reference behavior that
 * {@link BlockStreamInfoImplTest} asserts parity against.
 */
@ExtendWith(MockitoExtension.class)
class BlockRecordInfoImplTest {
    private static final Timestamp CURRENT_BLOCK_TIME =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState<BlockInfo> blockInfoState;

    @Mock
    private ReadableSingletonState<RunningHashes> runningHashesState;

    /** A distinct, deterministic 48-byte (SHA-384-length) hash filled with the given byte. */
    private static Bytes hash(final int seed) {
        final var bytes = new byte[HASH_SIZE];
        java.util.Arrays.fill(bytes, (byte) seed);
        return Bytes.wrap(bytes);
    }

    /** Concatenates the given hashes into a single {@link Bytes}, mirroring {@code BlockInfo.blockHashes}. */
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

    private static BlockInfo blockInfo(final long lastBlockNumber, final Bytes blockHashes) {
        return BlockInfo.newBuilder()
                .lastBlockNumber(lastBlockNumber)
                .blockHashes(blockHashes)
                .firstConsTimeOfCurrentBlock(CURRENT_BLOCK_TIME)
                .build();
    }

    @Test
    void blockNoIsLastBlockNumberPlusOne() {
        assertEquals(0L, new BlockRecordInfoImpl(blockInfo(-1L, Bytes.EMPTY), RunningHashes.DEFAULT).blockNo());
        assertEquals(1L, new BlockRecordInfoImpl(blockInfo(0L, hash(1)), RunningHashes.DEFAULT).blockNo());
        assertEquals(666L, new BlockRecordInfoImpl(blockInfo(665L, hash(1)), RunningHashes.DEFAULT).blockNo());
    }

    @Test
    void blockTimestampIsFirstConsTimeOfCurrentBlock() {
        final var subject = new BlockRecordInfoImpl(blockInfo(5L, hash(1)), RunningHashes.DEFAULT);
        assertEquals(CURRENT_BLOCK_TIME, subject.blockTimestamp());
    }

    @Test
    void prngSeedIsNMinus3RunningHash() {
        final var nMinus3 = hash(7);
        final var runningHashes =
                RunningHashes.newBuilder().nMinus3RunningHash(nMinus3).build();
        final var subject = new BlockRecordInfoImpl(blockInfo(5L, hash(1)), runningHashes);
        assertEquals(nMinus3, subject.prngSeed());
    }

    @Test
    void blockHashByBlockNumberResolvesWithinRangeAndNullsOutside() {
        // lastBlockNumber = 3 with hashes for blocks 1, 2, 3 (rightmost is block 3)
        final var subject =
                new BlockRecordInfoImpl(blockInfo(3L, concat(hash(1), hash(2), hash(3))), RunningHashes.DEFAULT);

        assertEquals(hash(3), subject.blockHashByBlockNumber(3L), "rightmost hash is the last block");
        assertEquals(hash(2), subject.blockHashByBlockNumber(2L));
        assertEquals(hash(1), subject.blockHashByBlockNumber(1L), "leftmost available block");
        assertNull(subject.blockHashByBlockNumber(0L), "older than the available window");
        assertNull(subject.blockHashByBlockNumber(4L), "future block");
        assertNull(subject.blockHashByBlockNumber(-1L), "negative block number");
    }

    @Test
    void blockHashByBlockNumberIsNullAtGenesis() {
        final var subject = new BlockRecordInfoImpl(blockInfo(-1L, Bytes.EMPTY), RunningHashes.DEFAULT);
        assertNull(subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(-1L));
    }

    @Test
    void fromStateReadsBlockInfoAndRunningHashesSingletons() {
        final var nMinus3 = hash(9);
        given(state.getReadableStates(BlockRecordService.NAME)).willReturn(readableStates);
        given(readableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(readableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashesState);
        given(blockInfoState.get()).willReturn(blockInfo(42L, hash(1)));
        given(runningHashesState.get())
                .willReturn(
                        RunningHashes.newBuilder().nMinus3RunningHash(nMinus3).build());

        final var subject = BlockRecordInfoImpl.from(state);

        assertEquals(43L, subject.blockNo());
        assertEquals(CURRENT_BLOCK_TIME, subject.blockTimestamp());
        assertEquals(nMinus3, subject.prngSeed());
    }
}
