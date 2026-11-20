// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.util.Arrays;

/** Opens the timestamp leaf of a Hiero block root that also authenticates a CLPR manifest. */
public final class ClprProofTimestamp {
    private ClprProofTimestamp() {}

    /** Builds the independent path from the block timestamp, the left child of the block root. */
    @NonNull
    static MerklePath pathFor(
            @NonNull final MerklePath statePath,
            @NonNull final Bytes serializedTimestamp,
            @NonNull final MessageDigest digest) {
        // The last sibling on the state path is the hashed timestamp. The preceding steps
        // reconstruct the complete right subtree, which is the timestamp leaf's only sibling.
        final var siblings = statePath.siblings();
        final var rightSubtree = StateProofVerifier.computeBlockRootHashFromPath(
                statePath
                        .copyBuilder()
                        .siblings(siblings.subList(0, siblings.size() - 1))
                        .build(),
                digest);
        return MerklePath.newBuilder()
                .timestampLeaf(serializedTimestamp)
                .siblings(
                        SiblingNode.newBuilder().hash(Bytes.wrap(rightSubtree)).build())
                .nextPathIndex(-1)
                .build();
    }

    /**
     * Reads an optional timestamp from the canonical block root position. The caller must
     * authenticate the supplied root independently before using the timestamp.
     */
    @Nullable
    public static Timestamp verifiedTimestamp(
            @NonNull final StateProof proof,
            @NonNull final byte[] authenticatedRoot,
            @NonNull final MessageDigest digest)
            throws ParseException {
        final var path = proof.paths().stream()
                .filter(MerklePath::hasTimestampLeaf)
                .findFirst()
                .orElse(null);
        if (path == null || path.nextPathIndex() != -1 || path.siblings().size() != 1) {
            return null;
        }
        final var sibling = path.siblings().getFirst();
        if (sibling.isLeft() || sibling.hash().length() != authenticatedRoot.length) {
            return null;
        }
        final var rawTimestamp = path.timestampLeafOrThrow();
        final var timestampHash = HashUtils.computeRawLeafHash(digest, rawTimestamp);
        final var computedRoot =
                HashUtils.joinHashes(digest, timestampHash, sibling.hash().toByteArray());
        return Arrays.equals(authenticatedRoot, computedRoot) ? Timestamp.PROTOBUF.parseStrict(rawTimestamp) : null;
    }
}
