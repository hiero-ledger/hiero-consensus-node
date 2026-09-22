// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for validating {@link StateProof} messages.
 *
 * <p>Provides Merkle-path reconstruction helpers — {@link #computeBlockRootHash(StateProof)},
 * {@link #computeBlockRootHashFromPath(MerklePath)}, and {@link #verifyPath(MerklePath, byte[])} —
 * that callers combine with a {@link TssVerifier} (e.g. {@link NativeTssVerifier}) to perform the
 * BLS aggregate signature check against the peer ledger's verification key.
 *
 * <p>{@link #verifyPath(MerklePath, byte[])} performs Merkle reconstruction only and only accepts
 * {@code stateItemLeaf} paths (the only leaf type CLPR produces).
 */
public final class StateProofVerifier {

    private static final Logger log = LogManager.getLogger(StateProofVerifier.class);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private StateProofVerifier() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Computes the block root hash from the proof's Merkle paths without performing any
     * signature check. Used by callers (e.g. CLPR system-contract verifiers) that need the
     * hash to hand off to an external TSS verifier.
     *
     * @param stateProof the state proof to compute the root hash of
     * @return the computed block root hash
     * @throws NullPointerException  if {@code stateProof} is null
     * @throws IllegalStateException if the paths are structurally invalid
     */
    @NonNull
    public static byte[] computeBlockRootHash(@NonNull final StateProof stateProof) {
        requireNonNull(stateProof, "stateProof must not be null");
        final var rootHash = computeRootHash(stateProof.paths());
        log.info(
                "StateProofVerifier.computeBlockRootHash PASS paths={} rootHash={}",
                stateProof.paths().size(),
                shortHex(rootHash));
        return rootHash;
    }

    /**
     * Computes the block root hash from a single self-contained leaf-to-block-root
     * {@link MerklePath} — the form used by CLPR bundle paths, where each path independently
     * authenticates to the block root and has {@code nextPathIndex = -1}.
     *
     * <p>Unlike {@link #computeBlockRootHash(StateProof)} (which expects a single connected
     * proof tree), this works on one independent path; callers should subsequently verify the
     * remaining bundle paths against the returned hash via {@link #verifyPath(MerklePath, byte[])}.
     *
     * @param path the merkle path to compute the block root hash from
     * @return the computed block root hash
     * @throws NullPointerException  if {@code path} is null
     * @throws IllegalStateException if the path has no leaf or explicit hash
     */
    @NonNull
    public static byte[] computeBlockRootHashFromPath(@NonNull final MerklePath path) {
        requireNonNull(path, "path must not be null");
        if (!hasBaseHash(path)) {
            throw new IllegalStateException("MerklePath has no leaf or explicit hash");
        }
        final var rootHash = computeBasePathHash(path);
        log.debug(
                "StateProofVerifier.computeBlockRootHashFromPath PASS siblings={} nextPathIndex={} rootHash={}",
                path.siblings().size(),
                path.nextPathIndex(),
                shortHex(rootHash));
        return rootHash;
    }

    /**
     * Verifies a single independent {@link MerklePath} against an expected block-root hash.
     *
     * <p>Use this for CLPR bundle paths, which are each fully self-contained leaf-to-block-root
     * paths with {@code nextPathIndex = -1}. Passing them to {@link #computeBlockRootHash(StateProof)}
     * would fail because the stack-based reconstruction requires a single root entry; N independent
     * paths leave N entries on the stack and throw {@link IllegalStateException}.
     *
     * <p>The leaf hash is computed via {@link HashUtils#computeVirtualMapStateLeafHash} —
     * CLPR state proofs contain only {@code stateItemLeaf} paths from VirtualMap.
     *
     * @param path the merkle path to verify (must have a leaf or explicit hash)
     * @param expectedBlockRootHash the expected block root hash to compare against
     * @return {@code true} if the path correctly authenticates to {@code expectedBlockRootHash}
     */
    public static boolean verifyPath(@NonNull final MerklePath path, @NonNull final byte[] expectedBlockRootHash) {
        requireNonNull(path, "path must not be null");
        requireNonNull(expectedBlockRootHash, "expectedBlockRootHash must not be null");
        if (!hasBaseHash(path)) {
            log.debug("verifyPath: path has no base hash (no leaf and no explicit hash)");
            return false;
        }
        final byte[] computed;
        try {
            computed = computeBasePathHash(path);
        } catch (final IllegalStateException e) {
            // Defensive: hasBaseHash currently admits leaf types that computeLeafHash does not yet
            // support (blockItemLeaf, timestampLeaf), which would otherwise propagate as an
            // unstructured runtime error to the caller. Treat as a verification failure instead so
            // the CLPR precompile can revert with CLPR_BUNDLE_VERIFICATION_FAILED rather than
            // consuming all gas via a precompile-internal error.
            log.debug("verifyPath: could not compute base hash for path: {}", e.getMessage());
            return false;
        }
        if (!Arrays.equals(computed, expectedBlockRootHash)) {
            log.debug(
                    "verifyPath: hash mismatch; computed={} expected={} siblings={}",
                    bytesToHex(computed),
                    bytesToHex(expectedBlockRootHash),
                    path.siblings().size());
            return false;
        }
        return true;
    }

    /**
     * Computes the root hash from a list of {@link MerklePath} objects.
     *
     * <p>This method reconstructs the Merkle tree using a stack-based algorithm:
     * <ul>
     *   <li>Leaf paths: Compute their hash and push to stack with parent index</li>
     *   <li>Internal paths: Pop child hashes from stack, combine them, push result</li>
     *   <li>Final result: Single hash on stack with {@code nextPathIndex = -1}</li>
     * </ul>
     *
     * <p><b>Data Structure Invariants:</b>
     * <ol>
     *   <li>All child paths have lower index than parent paths in the list</li>
     *   <li>All left child paths have lower index than their right siblings</li>
     * </ol>
     *
     * <p><b>Algorithm Steps:</b>
     * <ol>
     *   <li>Initialize empty stack for hash-index pairs</li>
     *   <li>For each path in order:
     *     <ul>
     *       <li>If leaf path: compute hash from leaf + siblings, push to stack</li>
     *       <li>If internal path: collect all children from stack, combine based on count (1 or 2), push result</li>
     *     </ul>
     *   </li>
     *   <li>Verify single root hash remains with {@code nextPathIndex = -1}</li>
     * </ol>
     *
     * <p>This ensures efficient reconstruction with O(total nodes) time complexity.
     *
     * @param paths the list of merkle paths ordered such that children appear before parents
     * @return the computed root hash
     * @throws IllegalStateException if the path structure violates invariants
     */
    @NonNull
    private static byte[] computeRootHash(@NonNull final List<MerklePath> paths) {
        requireNonNull(paths, "paths must not be null");

        if (paths.isEmpty()) {
            throw new IllegalStateException("Cannot compute root hash from empty path list");
        }

        final Deque<HashIndexPair> stack = new ArrayDeque<>();

        for (int i = 0; i < paths.size(); i++) {
            final var path = paths.get(i);

            if (hasBaseHash(path)) {
                // Base path: compute hash and push to stack
                final byte[] basePathHash = computeBasePathHash(path);
                stack.push(new HashIndexPair(basePathHash, path.nextPathIndex()));
            } else {
                // Internal path: must have child hashes on stack
                if (stack.isEmpty() || stack.peek().nextPathIndex() != i) {
                    throw new IllegalStateException("Expected child path hashes for non-base path at index " + i);
                }

                // Collect all children pointing to this path
                final var childHashes = new ArrayList<byte[]>();
                while (!stack.isEmpty() && stack.peek().nextPathIndex() == i) {
                    childHashes.add(stack.pop().hash());
                }

                // Reverse to get left-to-right order (children were collected right-to-left)
                Collections.reverse(childHashes);

                // Compute this path's hash and push to stack
                final byte[] pathHash = computeInternalPathHash(path, childHashes);
                stack.push(new HashIndexPair(pathHash, path.nextPathIndex()));
            }
        }

        // Verify exactly one root hash remains
        if (stack.size() != 1 || stack.peek().nextPathIndex() != -1) {
            throw new IllegalStateException(
                    "Expected exactly one root hash with nextPathIndex=-1, but found " + stack.size() + " hashes");
        }

        return stack.pop().hash();
    }

    /**
     * Computes the hash for a base {@link MerklePath} (i.e. a path that starts from leaf bytes or an explicit hash).
     *
     * <p>The computation proceeds from base to root:
     * <ol>
     *   <li>Hash the leaf with 0x00 prefix (if present), or use the explicit base hash</li>
     *   <li>Combine with siblings walking toward the root</li>
     * </ol>
     *
     * @param path the base merkle path
     * @return the computed hash reaching the path's endpoint
     */
    @NonNull
    private static byte[] computeBasePathHash(@NonNull final MerklePath path) {
        final byte[] baseHash = path.hasHash() ? path.hash().toByteArray() : computeLeafHash(path);
        return computeRootOfSiblings(path.siblings(), baseHash);
    }

    /**
     * Computes the hash for an internal (non-leaf) {@link MerklePath}.
     *
     * <p>Internal paths represent shared segments in aggregated proofs. The computation:
     * <ul>
     *   <li>If 1 child: wrap with single-child hash (0x01 prefix), then combine with siblings</li>
     *   <li>If 2 children: join with internal node hash (0x02 prefix), then combine with siblings</li>
     * </ul>
     *
     * @param path the internal merkle path
     * @param childHashes the list of child hashes (1 or 2 elements)
     * @return the computed hash reaching the path's endpoint
     * @throws IllegalStateException if number of child hashes is invalid
     */
    @NonNull
    private static byte[] computeInternalPathHash(
            @NonNull final MerklePath path, @NonNull final List<byte[]> childHashes) {
        final byte[] baseHash =
                switch (childHashes.size()) {
                    case 1 -> computeSingleChildHash(childHashes.get(0));
                    case 2 -> joinHashes(childHashes.get(0), childHashes.get(1));
                    default ->
                        throw new IllegalStateException(
                                "Internal path must have 1 or 2 children, but found " + childHashes.size());
                };

        return computeRootOfSiblings(path.siblings(), baseHash);
    }

    /**
     * Computes the final hash by combining a starting hash with a list of sibling nodes.
     *
     * <p>This walks up the tree from the starting hash, combining with each sibling:
     * <ul>
     *   <li>Empty sibling (0-length hash): single-child promotion (0x01 prefix)</li>
     *   <li>{@code isLeft = true}: sibling is left, compute hash(sibling, current)</li>
     *   <li>{@code isLeft = false}: sibling is right, compute hash(current, sibling)</li>
     * </ul>
     *
     * @param siblings the list of sibling nodes from near-to-far
     * @param startHash the starting hash (leaf or combined child hash)
     * @return the computed hash after combining with all siblings
     */
    @NonNull
    private static byte[] computeRootOfSiblings(
            @NonNull final List<SiblingNode> siblings, @NonNull final byte[] startHash) {
        byte[] computedHash = startHash;

        for (final SiblingNode sibling : siblings) {
            final byte[] siblingBytes = sibling.hash().toByteArray();

            if (siblingBytes.length == 0) {
                // Empty sibling = single-child level, promote current hash
                computedHash = computeSingleChildHash(computedHash);
            } else {
                if (sibling.isLeft()) {
                    // Sibling is on the left
                    computedHash = joinHashes(siblingBytes, computedHash);
                } else {
                    // Sibling is on the right
                    computedHash = joinHashes(computedHash, siblingBytes);
                }
            }
        }

        return computedHash;
    }

    /**
     * Computes SHA-384(0x00 || stateItemBytes) for a VirtualMap state-item leaf.
     *
     * @param path a merkle path containing a {@code stateItemLeaf}
     * @return the computed leaf hash
     * @throws IllegalStateException if no stateItemLeaf is set
     */
    @NonNull
    private static byte[] computeLeafHash(@NonNull final MerklePath path) {
        if (path.hasStateItemLeaf()) {
            return HashUtils.computeVirtualMapStateLeafHash(HashUtils.newMessageDigest(), path.stateItemLeaf());
        }
        throw new IllegalStateException("MerklePath does not contain a stateItemLeaf");
    }

    /**
     * Computes the hash for a single-child node with the single-child prefix (0x01).
     *
     * <p>Format: SHA-384(0x01 || childHash)
     *
     * @param childHash the hash of the single child
     * @return the computed single-child hash
     */
    @NonNull
    private static byte[] computeSingleChildHash(@NonNull final byte[] childHash) {
        return HashUtils.computeSingleChildHash(HashUtils.newMessageDigest(), childHash);
    }

    /**
     * Joins two child hashes to create an internal node hash with the internal prefix (0x02).
     *
     * <p>Format: SHA-384(0x02 || leftHash || rightHash)
     *
     * @param leftHash the hash of the left child
     * @param rightHash the hash of the right child
     * @return the computed internal node hash
     */
    @NonNull
    private static byte[] joinHashes(@NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return HashUtils.joinHashes(HashUtils.newMessageDigest(), leftHash, rightHash);
    }

    private static boolean hasBaseHash(@NonNull final MerklePath path) {
        return path.hasHash() || path.hasStateItemLeaf() || path.hasBlockItemLeaf() || path.hasTimestampLeaf();
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes the byte array to convert
     * @return the hexadecimal string representation
     */
    private static String bytesToHex(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String shortHex(final byte[] bytes) {
        final var hex = bytesToHex(bytes);
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }

    /**
     * Internal record for tracking hash values and their parent path indices during
     * root hash computation.
     */
    private record HashIndexPair(@NonNull byte[] hash, int nextPathIndex) {}
}
