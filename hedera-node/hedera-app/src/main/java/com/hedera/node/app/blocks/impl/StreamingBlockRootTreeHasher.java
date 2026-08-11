// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;

/**
 * The plain statement of the block root tree: every branch goes into an {@link IncrementalStreamingHasher} and
 * the root comes out. Each sibling is likewise the streamed root of the branch range beneath it.
 *
 * <p>This takes no shortcuts and assumes nothing about which branches are empty, which makes it the yardstick
 * for {@link CachedReservedHalfBlockRootTreeHasher} and the implementation to reach for first if a reserved
 * branch is ever assigned. Production should use {@link CachedReservedHalfBlockRootTreeHasher}.
 */
public final class StreamingBlockRootTreeHasher implements BlockRootTreeHasher {
    /**
     * The instance to use. This class declares no instance fields and every call allocates its own hasher,
     * so it is immutable and safe to use concurrently — callers, including tests running in parallel, need
     * no coordination. It exists only so the implementation can be passed as a
     * {@link BlockRootTreeHasher} without each caller allocating an identical stateless object.
     */
    public static final StreamingBlockRootTreeHasher INSTANCE = new StreamingBlockRootTreeHasher();

    private StreamingBlockRootTreeHasher() {
        // Use INSTANCE; there is no per-instance state to justify another
    }

    @Override
    public RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        BlockRootTreeHasher.validate(timestampLeafHash, slots);

        final var subtreesRootHash = streamedRootOf(slots);
        final var blockRootHash = BlockImplUtils.hashInternalNode(timestampLeafHash, subtreesRootHash);

        // Branch 1 is the leftmost leaf, so the sibling at level i is the root of the branch range
        // [2^i, 2^(i+1)) — the half of branch 1's ancestor that branch 1 is not in
        final var siblings = new MerkleSiblingHash[SIBLING_COUNT];
        for (int level = 0; level < SIBLING_COUNT; level++) {
            final int from = 1 << level;
            final int to = from << 1;
            siblings[level] = new MerkleSiblingHash(false, streamedRootOf(Arrays.copyOfRange(slots, from, to)));
        }
        return new RootAndSiblingHashes(blockRootHash, siblings);
    }

    /**
     * Folds pre-hashed nodes into a single root with {@link IncrementalStreamingHasher}.
     *
     * @param nodes the pre-hashed nodes
     * @return the root hash
     */
    public static Bytes streamedRootOf(@NonNull final Bytes[] nodes) {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        for (final var node : nodes) {
            hasher.addNodeByHash(node.toByteArray());
        }
        return Bytes.wrap(hasher.computeRootHash());
    }
}
