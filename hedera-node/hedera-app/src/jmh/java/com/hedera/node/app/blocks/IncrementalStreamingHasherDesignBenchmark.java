// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;

import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares the current {@code IncrementalStreamingHasher} (backed by an {@code ArrayDeque<byte[]>}, used purely as
 * a stack via {@code addLast}/{@code removeLast}) against a faithful reproduction of its pre-refactor implementation
 * (backed by a {@code LinkedList<byte[]>}). Both build the identical streaming Merkle tree fold-up
 * ({@code addNodeByHash}) over the same sequence of pre-hashed leaves and compute the same root hash
 * ({@code computeRootHash}) — this isolates the data-structure overhead (a {@code Node} object allocated and
 * discarded per push/pop with {@code LinkedList} vs. no per-element allocation with {@code ArrayDeque}) from
 * everything else in the block-stream pipeline.
 *
 * <p>This runs once per hasher per block item across up to 5 hashers (consensus header, input, output, state
 * changes, trace data trees), so the per-call overhead multiplies across a whole block's item count.
 */
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IncrementalStreamingHasherDesignBenchmark {

    @Param({"1000", "20000", "200000"})
    private int leafCount;

    private byte[][] leaves;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"IncrementalStreamingHasherDesignBenchmark", "-v", "EXTRA"});
    }

    @Setup(Level.Trial)
    public void setup() {
        final var random = new Random(42);
        leaves = new byte[leafCount][];
        for (int i = 0; i < leafCount; i++) {
            // Already "hashed" 48-byte SHA-384-sized leaves, matching addNodeByHash's expected input; this isolates
            // the stack data structure from the (unrelated, unchanged) leaf-hashing cost.
            leaves[i] = new byte[48];
            random.nextBytes(leaves[i]);
        }

        final var expected = current_arrayDeque();
        final var actual = legacy_linkedList();
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new IllegalStateException("Legacy and current hasher implementations produced different roots");
        }
    }

    // Disabled by default (no @Benchmark) so a plain :app:jmh run doesn't execute it — uncomment to re-enable.
    // @Benchmark
    public byte[] current_arrayDeque() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), new ArrayList<>(), 0);
        for (final var leaf : leaves) {
            hasher.addNodeByHash(leaf);
        }
        return hasher.computeRootHash();
    }

    // Disabled by default (no @Benchmark) so a plain :app:jmh run doesn't execute it — uncomment to re-enable.
    // @Benchmark
    public byte[] legacy_linkedList() {
        final var hasher = new LegacyLinkedListHasher(sha384DigestOrThrow());
        for (final var leaf : leaves) {
            hasher.addNodeByHash(leaf);
        }
        return hasher.computeRootHash();
    }

    /**
     * Faithful reproduction of {@code IncrementalStreamingHasher}'s implementation before it switched from
     * {@code LinkedList} to {@code ArrayDeque}, kept here only so this benchmark can compare against it.
     */
    private static final class LegacyLinkedListHasher {
        private final MessageDigest digest;
        private final LinkedList<byte[]> hashList = new LinkedList<>();
        private long leafCount;

        LegacyLinkedListHasher(final MessageDigest digest) {
            this.digest = digest;
        }

        void addNodeByHash(final byte[] hash) {
            hashList.add(hash);
            for (long n = leafCount; (n & 1L) == 1; n >>= 1) {
                final byte[] y = hashList.removeLast();
                final byte[] x = hashList.removeLast();
                hashList.add(BlockImplUtils.hashInternalNode(digest, x, y));
            }
            leafCount++;
        }

        byte[] computeRootHash() {
            if (hashList.isEmpty()) {
                return BlockStreamManager.HASH_OF_ZERO_BYTES;
            }
            if (hashList.size() == 1) {
                return hashList.getFirst();
            }
            byte[] merkleRootHash = hashList.getLast();
            for (int i = hashList.size() - 2; i >= 0; i--) {
                merkleRootHash = BlockImplUtils.hashInternalNode(digest, hashList.get(i), merkleRootHash);
            }
            return merkleRootHash;
        }
    }
}
