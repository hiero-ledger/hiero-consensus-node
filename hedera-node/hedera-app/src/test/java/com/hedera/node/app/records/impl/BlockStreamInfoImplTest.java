// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.hiero.base.crypto.DigestType;
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
        final var bytes = new byte[DigestType.SHA_384.digestLength()];
        java.util.Arrays.fill(bytes, (byte) seed);
        return Bytes.wrap(bytes);
    }

    // trailingOutputHashes are chained running hashes whose size follows the configured digest type.
    private static Bytes runningHash(final int seed) {
        return runningHash(seed, DigestType.SHA_384);
    }

    private static Bytes runningHash(final int seed, final DigestType digestType) {
        final var bytes = new byte[digestType.digestLength()];
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

    /** The genesis "block -1" hash-of-zero entry that heads every real trailing window, sized to the window stride. */
    private static Bytes genesis(final int strideBytes) {
        return Bytes.wrap(new byte[strideBytes]);
    }

    /** A fully-populated {@link BlockStreamInfo} whose last block was built with SHA-384 (48-byte subtree roots). */
    private static BlockStreamInfo info(final long blockNumber, final Bytes trailingBlockHashes) {
        return info(blockNumber, trailingBlockHashes, DigestType.SHA_384);
    }

    /**
     * A fully-populated {@link BlockStreamInfo} whose last block was built with {@code blockDigest}, sizing the
     * persisted consensus-header-root to that digest. {@code reconstructLastBlockHash} infers the block's digest
     * from this length, so a SHA-256 block must carry a 32-byte root (not a 48-byte one).
     */
    private static BlockStreamInfo info(
            final long blockNumber, final Bytes trailingBlockHashes, final DigestType blockDigest) {
        return info(blockNumber, trailingBlockHashes, blockDigest, false);
    }

    /**
     * As {@link #info(long, Bytes, DigestType)} but with an explicit {@code trailing_block_hashes_compacted} flag —
     * {@code true} for a native (active-size) window, {@code false} for a padded 48-byte window.
     */
    private static BlockStreamInfo info(
            final long blockNumber,
            final Bytes trailingBlockHashes,
            final DigestType blockDigest,
            final boolean compacted) {
        return BlockStreamInfo.newBuilder()
                .blockNumber(blockNumber)
                .blockTime(BLOCK_TIME)
                .blockEndTime(BLOCK_TIME)
                .trailingBlockHashes(trailingBlockHashes)
                .trailingBlockHashesCompacted(compacted)
                .trailingOutputHashes(Bytes.EMPTY)
                .intermediatePreviousBlockRootHashes(List.of())
                .intermediateBlockRootsLeafCount(0L)
                .rightmostPrecedingStateChangesTreeHashes(List.of())
                .numPrecedingStateChangesItems(0L)
                .startOfBlockStateHash(hash(11))
                .consensusHeaderRootHash(runningHash(12, blockDigest))
                .inputTreeRootHash(hash(13))
                .outputItemRootHash(hash(14))
                .traceDataRootHash(hash(15))
                .build();
    }

    @Test
    void blockNoIsBlockNumberPlusOne() {
        assertEquals(
                0L,
                new BlockStreamInfoImpl(info(-1L, Bytes.EMPTY), DigestType.SHA_384).blockNo(),
                "genesis matches records");
        assertEquals(1L, new BlockStreamInfoImpl(info(0L, Bytes.EMPTY), DigestType.SHA_384).blockNo());
        assertEquals(667L, new BlockStreamInfoImpl(info(666L, hash(1)), DigestType.SHA_384).blockNo());
    }

    @Test
    void blockTimestampUsesBlockTimeWithDefaultFallback() {
        assertEquals(BLOCK_TIME, new BlockStreamInfoImpl(info(5L, hash(1)), DigestType.SHA_384).blockTimestamp());
        final var noTime = BlockStreamInfo.newBuilder().blockNumber(5L).build();
        assertEquals(Timestamp.DEFAULT, new BlockStreamInfoImpl(noTime, DigestType.SHA_384).blockTimestamp());
    }

    @Test
    void prngSeedIsLeftmostTrailingOutputHashOnceFourArePresent() {
        // Four or more output hashes: seed is the leftmost (n-minus-3 running hash).
        final var four = concat(runningHash(1), runningHash(2), runningHash(3), runningHash(4));
        assertEquals(
                runningHash(1),
                new BlockStreamInfoImpl(
                                BlockStreamInfo.newBuilder()
                                        .trailingOutputHashes(four)
                                        .build(),
                                DigestType.SHA_384)
                        .prngSeed());
        // Fewer than four: not yet available.
        final var three = concat(runningHash(1), runningHash(2), runningHash(3));
        assertNull(new BlockStreamInfoImpl(
                        BlockStreamInfo.newBuilder().trailingOutputHashes(three).build(), DigestType.SHA_384)
                .prngSeed());
        assertNull(new BlockStreamInfoImpl(
                        BlockStreamInfo.newBuilder()
                                .trailingOutputHashes(Bytes.EMPTY)
                                .build(),
                        DigestType.SHA_384)
                .prngSeed());
    }

    @Test
    void prngSeedUsesConfiguredDigestLength() {
        final var sha256 = DigestType.SHA_256;
        final var four =
                concat(runningHash(1, sha256), runningHash(2, sha256), runningHash(3, sha256), runningHash(4, sha256));
        assertEquals(
                runningHash(1, sha256),
                new BlockStreamInfoImpl(
                                BlockStreamInfo.newBuilder()
                                        .trailingOutputHashes(four)
                                        .build(),
                                sha256)
                        .prngSeed());
        final var three = concat(runningHash(1, sha256), runningHash(2, sha256), runningHash(3, sha256));
        assertNull(new BlockStreamInfoImpl(
                        BlockStreamInfo.newBuilder().trailingOutputHashes(three).build(), sha256)
                .prngSeed());
    }

    @Test
    void prngSeedDecodesMixedCutoverWindow() {
        // After a cutover block closes with <4 results, the output window is a mix of pre-cutover 48-byte
        // SHA-384 seeds and post-cutover 32-byte SHA-256 hashes. Here the two leftmost entries are still
        // 48-byte seeds, so the PRNG seed (the leftmost running hash) is the full 48-byte value.
        final var sha256 = DigestType.SHA_256;
        final var mixed = concat(
                runningHash(1, DigestType.SHA_384),
                runningHash(2, DigestType.SHA_384),
                runningHash(3, sha256),
                runningHash(4, sha256));
        assertEquals(160, mixed.length(), "Two 48-byte seeds plus two 32-byte hashes");
        assertEquals(
                runningHash(1, DigestType.SHA_384),
                new BlockStreamInfoImpl(
                                BlockStreamInfo.newBuilder()
                                        .trailingOutputHashes(mixed)
                                        .build(),
                                sha256)
                        .prngSeed(),
                "prngSeed must return the full 48-byte leftmost pre-cutover running hash");
    }

    /** A 32-byte SHA-256 hash left-padded with 16 leading zeros to the uniform 48-byte stored size. */
    private static Bytes paddedSha256Hash(final int seed) {
        final var out = new byte[DigestType.SHA_384.digestLength()];
        final var real = new byte[DigestType.SHA_256.digestLength()];
        java.util.Arrays.fill(real, (byte) seed);
        System.arraycopy(real, 0, out, out.length - real.length, real.length);
        return Bytes.wrap(out);
    }

    @Test
    void blockHashByBlockNumberDecodesMixedPaddedWindowWithSha256() {
        // After a cutover, trailingBlockHashes is a uniform 48-byte-per-entry window mixing genuine 48-byte SHA-384
        // record hashes (pre-cutover, left) with left-padded 32-byte SHA-256 block hashes (post-cutover, right).
        // blockNumber = 3, trailing covers blocks 0, 1 (SHA-384) and 2 (padded SHA-256); block 3 is reconstructed.
        // The 48-byte length is not divisible by 32, so the per-entry stride resolves unambiguously to 48. Block 3
        // is a SHA-256 block, so its consensus-header root is 32 bytes (see info(..., SHA_256)).
        final var trailing = concat(hash(1), hash(2), paddedSha256Hash(3));
        final var blockStreamInfo = info(3L, trailing, DigestType.SHA_256);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_256);
        final var reconstructedLast =
                BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_256);

        // Pre-cutover blocks keep their full 48-byte SHA-384 record hash.
        assertEquals(hash(1), subject.blockHashByBlockNumber(0L), "pre-cutover block 0 is a full 48-byte hash");
        assertEquals(48, (int) subject.blockHashByBlockNumber(0L).length());
        assertEquals(hash(2), subject.blockHashByBlockNumber(1L));
        // Post-cutover block's padding is stripped back to its real 32-byte SHA-256 hash.
        final var realSha256Block2 = runningHash(3, DigestType.SHA_256);
        assertEquals(
                realSha256Block2, subject.blockHashByBlockNumber(2L), "post-cutover block 2 is stripped to 32 bytes");
        assertEquals(32, (int) subject.blockHashByBlockNumber(2L).length());
        // The reconstructed last block's hash resolves and is 32 bytes under SHA-256.
        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(3L));
        assertEquals(32, (int) subject.blockHashByBlockNumber(3L).length());

        assertNull(subject.blockHashByBlockNumber(-1L), "older than the available window");
        assertNull(subject.blockHashByBlockNumber(4L), "future block");
    }

    @Test
    void blockHashByBlockNumberDecodesCompactedNativeWindowWithSha256() {
        // Post-compaction: once no genuine pre-cutover hashes remain, trailingBlockHashes is a uniform
        // 32-byte-per-entry native window (no padding). blockNumber = 3, so the genesis-consistent window is the
        // leading "block -1" entry followed by blocks 0, 1, 2 (four entries); block 3 is reconstructed. The leading
        // genesis entry makes the length 128 (not 96), which is not divisible by 48, so the stride is unambiguously
        // 32 even though a 96-byte native window would be divisible by both 48 and 32.
        final var block0 = runningHash(1, DigestType.SHA_256);
        final var block1 = runningHash(2, DigestType.SHA_256);
        final var block2 = runningHash(3, DigestType.SHA_256);
        final var trailing = concat(genesis(32), block0, block1, block2);
        assertEquals(128, (int) trailing.length(), "genesis entry + three native 32-byte entries");
        final var blockStreamInfo = info(3L, trailing, DigestType.SHA_256, true);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_256);
        final var reconstructedLast =
                BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_256);

        assertEquals(block0, subject.blockHashByBlockNumber(0L));
        assertEquals(32, (int) subject.blockHashByBlockNumber(0L).length());
        assertEquals(block1, subject.blockHashByBlockNumber(1L));
        assertEquals(block2, subject.blockHashByBlockNumber(2L));
        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(3L));
        assertEquals(32, (int) subject.blockHashByBlockNumber(3L).length());
        assertNull(subject.blockHashByBlockNumber(-1L), "older than the available window");
        assertNull(subject.blockHashByBlockNumber(4L), "future block");
    }

    @Test
    void blockHashByBlockNumberHandlesPostV0740TransientWindow() {
        // V0740 writes "all but the final record hash": 255 genuine 48-byte entries while blockNumber is large, so
        // min(blockNumber, 256) = 256 overstates the entry count and a raw length/count division yields stride 47.
        final long blockNumber = 1000L;
        final var entries = new Bytes[255];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = hash(i + 1);
        }
        final var trailing = concat(entries);
        assertEquals(255L * 48, trailing.length(), "post-V0740 shape: 255 entries of 48 bytes");
        final var blockStreamInfo = info(blockNumber, trailing, DigestType.SHA_256);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_256);
        final var reconstructedLast =
                BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_256);

        // The extended window has 256 entries covering blocks 745..1000
        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(blockNumber));
        assertEquals(32, (int) subject.blockHashByBlockNumber(blockNumber).length());
        assertEquals(hash(255), subject.blockHashByBlockNumber(blockNumber - 1), "genuine 48-byte entry intact");
        assertEquals(hash(254), subject.blockHashByBlockNumber(blockNumber - 2));
        assertEquals(hash(1), subject.blockHashByBlockNumber(blockNumber - 255), "oldest entry intact");
        assertNull(subject.blockHashByBlockNumber(blockNumber - 256), "older than the extended window");
    }

    @Test
    void blockHashByBlockNumberUnchangedForUniformWindows() {
        // Uniform 48-byte window with entries == count
        final var wide = new BlockStreamInfoImpl(info(3L, concat(hash(1), hash(2), hash(3))), DigestType.SHA_384);
        assertEquals(hash(1), wide.blockHashByBlockNumber(0L));
        assertEquals(hash(2), wide.blockHashByBlockNumber(1L));
        assertEquals(hash(3), wide.blockHashByBlockNumber(2L));
        assertEquals(48, (int) wide.blockHashByBlockNumber(3L).length());

        // Uniform 32-byte native window, genesis-consistent: the leading "block -1" entry plus blocks 0, 1, 2 for
        // blockNumber 3. The genesis entry makes the length 128 (not the ambiguous 96).
        final var sha256 = DigestType.SHA_256;
        final var narrow = new BlockStreamInfoImpl(
                info(
                        3L,
                        concat(genesis(32), runningHash(1, sha256), runningHash(2, sha256), runningHash(3, sha256)),
                        sha256,
                        true),
                sha256);
        assertEquals(runningHash(1, sha256), narrow.blockHashByBlockNumber(0L));
        assertEquals(runningHash(2, sha256), narrow.blockHashByBlockNumber(1L));
        assertEquals(runningHash(3, sha256), narrow.blockHashByBlockNumber(2L));
        assertEquals(32, (int) narrow.blockHashByBlockNumber(3L).length());
    }

    @Test
    void blockHashByBlockNumberIsNullAtGenesis() {
        final var subject = new BlockStreamInfoImpl(info(-1L, Bytes.EMPTY), DigestType.SHA_384);
        assertNull(subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(-1L));
    }

    @Test
    void blockHashByBlockNumberReconstructsLastBlockAndResolvesTrailing() {
        // blockNumber = 2, trailing covers blocks 0 and 1 (genesis-consistent); block 2's own hash is not in state.
        final var trailing = concat(hash(1), hash(2));
        final var blockStreamInfo = info(2L, trailing);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_384);
        final var reconstructedLast =
                BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_384);

        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(2L), "last block hash is reconstructed");
        assertEquals(hash(2), subject.blockHashByBlockNumber(1L), "previous block comes from trailing hashes");
        assertEquals(hash(1), subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(-1L), "older than the available window");
        assertNull(subject.blockHashByBlockNumber(3L), "future block");
    }

    @Test
    void blockHashByBlockNumberReconstructsTheVeryFirstBlock() {
        // blockNumber = 0: no prior trailing hashes; block 0's hash is reconstructed from genesis.
        final var blockStreamInfo = info(0L, Bytes.EMPTY);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_384);
        final var reconstructedLast =
                BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_384);

        assertEquals(reconstructedLast, subject.blockHashByBlockNumber(0L));
        assertNull(subject.blockHashByBlockNumber(1L));
        assertNull(subject.blockHashByBlockNumber(-1L));
    }

    @Test
    void extendedHashesAreStableAcrossRepeatedCalls() {
        // Genesis-consistent window: blockNumber = 2, trailing covers blocks 0 and 1.
        final var subject = new BlockStreamInfoImpl(info(2L, concat(hash(1), hash(2))), DigestType.SHA_384);
        assertEquals(subject.blockHashByBlockNumber(2L), subject.blockHashByBlockNumber(2L));
        assertEquals(subject.blockHashByBlockNumber(1L), subject.blockHashByBlockNumber(1L));
    }

    @Test
    void blockHashByBlockNumberLengthFollowsBlockDigest() {
        // At blockNumber == 0 the single resolvable hash is the reconstructed last-block hash. Its byte length
        // follows the digest the block was BUILT with (inferred from the persisted consensus-header-root length),
        // not the caller's current digest: 48 bytes for a SHA-384 block, 32 for a SHA-256 block.
        final var sha384 = new BlockStreamInfoImpl(info(0L, Bytes.EMPTY, DigestType.SHA_384), DigestType.SHA_384);
        final var sha256 = new BlockStreamInfoImpl(info(0L, Bytes.EMPTY, DigestType.SHA_256), DigestType.SHA_256);

        assertEquals(48, (int) sha384.blockHashByBlockNumber(0L).length(), "SHA-384 block hash is 48 bytes");
        assertEquals(32, (int) sha256.blockHashByBlockNumber(0L).length(), "SHA-256 block hash is 32 bytes");
        assertNotEquals(
                sha384.blockHashByBlockNumber(0L),
                sha256.blockHashByBlockNumber(0L),
                "a SHA-384 block and a SHA-256 block reconstruct to different hashes");
    }

    @Test
    void fromStateReadsBlockStreamInfoSingleton() {
        given(state.getReadableStates(BlockStreamService.NAME)).willReturn(readableStates);
        given(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(singletonState);
        given(singletonState.get()).willReturn(info(10L, hash(1)));

        final var subject = BlockStreamInfoImpl.from(state, DigestType.SHA_384);

        assertEquals(11L, subject.blockNo());
        assertEquals(BLOCK_TIME, subject.blockTimestamp());
    }

    @Test
    void blockNoHasFullParityWithRecordsAcrossLifecycle() {
        for (final long lastCompleted : new long[] {-1L, 0L, 5L, 665L}) {
            final var blocks = new BlockStreamInfoImpl(
                    info(lastCompleted, lastCompleted < 0 ? Bytes.EMPTY : hash(1)), DigestType.SHA_384);
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
        // Blocks-mode view: blockNumber = 2, trailing covers blocks 0 and 1 (genesis-consistent).
        final var trailing = concat(hash(1), hash(2));
        final var blockStreamInfo = info(2L, trailing);
        final var blocks = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_384);
        final var lastBlockHash = BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo, DigestType.SHA_384);

        // Equivalent records-mode view: lastBlockNumber = 2 and blockHashes include block 2's hash directly.
        final var records = new BlockRecordInfoImpl(
                BlockInfo.newBuilder()
                        .lastBlockNumber(2L)
                        .blockHashes(concat(hash(1), hash(2), lastBlockHash))
                        .firstConsTimeOfCurrentBlock(BLOCK_TIME)
                        .build(),
                RunningHashes.DEFAULT);

        for (long n = -1; n <= 3; n++) {
            assertEquals(
                    records.blockHashByBlockNumber(n),
                    blocks.blockHashByBlockNumber(n),
                    "blockHashByBlockNumber parity at block " + n);
        }
        assertEquals(records.blockNo(), blocks.blockNo());
    }

    /**
     * [PR 27696 comment, P2] The query path hard-codes {@code NUM_TRAILING_BLOCKS == 256} for the retention count,
     * but the producer uses the configured {@code BlockRecordStreamConfig.numOfBlockHashesInState()} (range 1..4096).
     * With a retention greater than 256, a mature native SHA-256 window becomes divisible by both strides, and the
     * capped count mis-derives stride 48 for what the producer stored as 32-byte native entries.
     *
     * <p>Concretely: retention 384, block 1000, a mature native window of {@code 384 * 32 == 12288} bytes — which is
     * also {@code 256 * 48}. The decoder caps the count at 256 and selects stride 48, so block 999 comes back as a
     * 48-byte splice instead of its real 32-byte SHA-256 hash.
     */
    @Test
    void blockHashByBlockNumberDecodesNativeWindowUnderLargerThan256Retention() {
        final int retention = 384;
        // A mature, fully-compacted native SHA-256 window: retention * 32 bytes, all genuine 32-byte entries. The
        // rightmost entry is the hash of block 999 (the last completed block before the current block 1000).
        final var entries = new Bytes[retention];
        for (int i = 0; i < retention; i++) {
            entries[i] = runningHash(i + 1, DigestType.SHA_256);
        }
        final var trailing = concat(entries);
        assertEquals(retention * 32, (int) trailing.length(), "retention * 32-byte native entries");

        final var blockStreamInfo = info(1000L, trailing, DigestType.SHA_256, true);
        final var subject = new BlockStreamInfoImpl(blockStreamInfo, DigestType.SHA_256, retention);

        final var block999 = subject.blockHashByBlockNumber(999L);
        assertEquals(
                32,
                (int) block999.length(),
                "block 999 is a native 32-byte SHA-256 hash; the persisted compacted flag fixes the stride directly");
    }
}
