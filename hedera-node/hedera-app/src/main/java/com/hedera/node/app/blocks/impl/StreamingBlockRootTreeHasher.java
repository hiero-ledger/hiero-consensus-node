// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.base.crypto.DigestType;

/**
 * The plain statement of the block root tree: every branch goes into an {@link IncrementalStreamingHasher} and
 * the root comes out. Each sibling is likewise the streamed root of the branch range beneath it.
 *
 * <p>This takes no shortcuts and assumes nothing about which branches are empty, which makes it the yardstick
 * for {@link CachedReservedHalfBlockRootTreeHasher} and the implementation to reach for first if a reserved
 * branch is ever assigned. Production should use {@link CachedReservedHalfBlockRootTreeHasher}.
 */
public final class StreamingBlockRootTreeHasher implements BlockRootTreeHasher {
    private static final Map<DigestType, StreamingBlockRootTreeHasher> INSTANCES = new EnumMap<>(DigestType.class);

    static {
        for (final var digestType : DigestType.values()) {
            INSTANCES.put(digestType, new StreamingBlockRootTreeHasher(digestType));
        }
    }

    private final DigestType digestType;
    private final Bytes emptySubtree;

    /**
     * Returns the hasher for the given digest type. Instances hold only their digest type and its empty-branch
     * hash, and every call allocates its own digest, so they are immutable and safe to use concurrently.
     *
     * @param digestType the digest type to build the tree with
     * @return the hasher for that digest type
     */
    public static StreamingBlockRootTreeHasher of(@NonNull final DigestType digestType) {
        return INSTANCES.get(requireNonNull(digestType));
    }

    private StreamingBlockRootTreeHasher(@NonNull final DigestType digestType) {
        this.digestType = digestType;
        this.emptySubtree = BlockRootTreeHasher.emptySubtreeFor(digestType);
    }

    @NonNull
    @Override
    public DigestType digestType() {
        return digestType;
    }

    @NonNull
    @Override
    public Bytes emptySubtree() {
        return emptySubtree;
    }

    @Override
    public RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        BlockRootTreeHasher.validate(timestampLeafHash, slots);

        final var subtreesRootHash = streamedRootOf(digestType::buildDigest, slots);
        final var blockRootHash =
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), timestampLeafHash, subtreesRootHash);

        // Branch 1 is the leftmost leaf, so the sibling at level i is the root of the branch range
        // [2^i, 2^(i+1)) — the half of branch 1's ancestor that branch 1 is not in
        final var siblings = new MerkleSiblingHash[SIBLING_COUNT];
        for (int level = 0; level < SIBLING_COUNT; level++) {
            final int from = 1 << level;
            final int to = from << 1;
            siblings[level] = new MerkleSiblingHash(
                    false, streamedRootOf(digestType::buildDigest, Arrays.copyOfRange(slots, from, to)));
        }
        return new RootAndSiblingHashes(blockRootHash, siblings);
    }

    /**
     * Folds pre-hashed nodes into a single root with {@link IncrementalStreamingHasher} using the given digest.
     *
     * @param digestSupplier supplies a fresh {@link MessageDigest} for hashing
     * @param nodes the pre-hashed nodes
     * @return the root hash
     */
    public static Bytes streamedRootOf(
            @NonNull final Supplier<MessageDigest> digestSupplier, @NonNull final Bytes[] nodes) {
        final var hasher = new IncrementalStreamingHasher(digestSupplier.get(), List.of(), 0);
        for (final var node : nodes) {
            hasher.addNodeByHash(node.toByteArray());
        }
        return Bytes.wrap(hasher.computeRootHash());
    }
}
