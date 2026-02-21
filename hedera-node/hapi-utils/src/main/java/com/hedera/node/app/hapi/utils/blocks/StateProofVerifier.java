// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
 * <p>This class provides functionality to verify that a state proof correctly proves the inclusion
 * of state items in a Merkle tree with a specific root hash. The verification process includes:
 * <ul>
 *   <li>Computing the root hash by reconstructing the Merkle tree from the paths</li>
 *   <li>Validating the structure and linkage of merkle paths via {@code nextPathIndex}</li>
 *   <li>Verifying the TSS signature over the computed root hash (Phase 2)</li>
 * </ul>
 *
 * <p><b>Phase 1 (Current):</b> Validates merkle path reconstruction and root hash computation.
 * TSS signature verification uses a mock implementation that compares the signature bytes
 * directly with the root hash.
 *
 * <p><b>Phase 2 (Future):</b> Will integrate real TSS signature verification using cryptographic
 * tools and ledger ID validation.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * StateProof proof = ...; // obtained from block stream
 * boolean isValid = StateProofVerifier.verify(proof);
 * if (isValid) {
 *     // State proof is cryptographically valid
 * }
 * }</pre>
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
     * Verifies a complete {@link StateProof}.
     *
     * <p>The verification process:
     * <ol>
     *   <li>Computes the root hash by reconstructing the Merkle tree from paths</li>
     *   <li>Validates that paths are properly structured (children before parents)</li>
     *   <li>Verifies the TSS signature over the computed root hash</li>
     * </ol>
     *
     * @param stateProof the state proof to verify
     * @return true if the state proof is valid, false otherwise
     * @throws NullPointerException if stateProof is null
     * @throws IllegalStateException if the proof structure is invalid
     */
    public static boolean verify(@NonNull final StateProof stateProof) {
        requireNonNull(stateProof, "stateProof must not be null");

        if (log.isDebugEnabled()) {
            logStateProof(stateProof);
        }

        // Compute root hash from merkle paths
        final byte[] rootHash = computeRootHash(stateProof.paths());

        // Verify TSS signature (Phase 1: mock verification)
        return verifyTssSignature(rootHash, stateProof);
    }

    /**
     * FOR TESTING ONLY: Verifies the computed root hash of a {@link StateProof} against an expected value.
     * This method bypasses TSS signature verification and is intended for use in unit tests.
     *
     * @param stateProof the state proof to verify
     * @param expectedRootHash the expected root hash
     * @return true if the computed root hash matches the expected root hash, false otherwise
     */
    public static boolean verifyRootHashForTest(
            @NonNull final StateProof stateProof, @NonNull final byte[] expectedRootHash) {
        requireNonNull(stateProof, "stateProof must not be null");
        requireNonNull(expectedRootHash, "expectedRootHash must not be null");
        final byte[] computedRootHash = computeRootHash(stateProof.paths());
        return Arrays.equals(computedRootHash, expectedRootHash);
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
     * Computes the hash of a leaf's raw bytes with the leaf prefix (0x00).
     *
     * <p>Format: SHA-384(0x00 || protobuf(legacy MerkleLeaf))
     *
     * <p>Although {@code MerkleLeaf} was removed after migrating its bytes into {@link MerklePath}'s leaf-bytes fields,
     * the leaf hash input remains the legacy {@code MerkleLeaf} protobuf encoding to preserve historical Merkle hashing.
     *
     * @param path a merkle path containing exactly one leaf-bytes field
     * @return the computed leaf hash
     */
    @NonNull
    private static byte[] computeLeafHash(@NonNull final MerklePath path) {
        if (path.hasStateItemLeaf()) {
            return HashUtils.computeStateItemLeafHash(HashUtils.newMessageDigest(), path.stateItemLeaf());
        }
        if (path.hasBlockItemLeaf()) {
            return HashUtils.computeBlockItemLeafHash(HashUtils.newMessageDigest(), path.blockItemLeaf());
        }
        if (path.hasTimestampLeaf()) {
            return HashUtils.computeTimestampLeafHash(HashUtils.newMessageDigest(), path.timestampLeaf());
        }
        throw new IllegalStateException("MerklePath does not contain leaf bytes");
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
     * Verifies the TSS signature over the computed root hash.
     *
     * <p><b>Phase 1 (Current):</b> Mock verification that compares the signature bytes
     * directly with the root hash. This is a placeholder for testing purposes.
     *
     * <p><b>Phase 2 (Future):</b> Will implement real TSS signature verification using
     * cryptographic libraries and ledger ID validation.
     *
     * @param rootHash the computed root hash from the merkle paths
     * @param stateProof the state proof containing the TSS signature
     * @return true if the signature is valid (Phase 1: if bytes match), false otherwise
     */
    private static boolean verifyTssSignature(@NonNull final byte[] rootHash, @NonNull final StateProof stateProof) {
        if (!stateProof.hasSignedBlockProof()) {
            return false;
        }

        final Bytes signatureBytes = stateProof.signedBlockProof().blockSignature();
        if (signatureBytes == null) {
            return false;
        }

        if (log.isDebugEnabled()) {
            logStateProof(stateProof);
        }

        // Phase 1: Mock verification - just compare bytes
        // Phase 2 TODO: Implement real TSS signature verification
        return Arrays.equals(rootHash, signatureBytes.toByteArray());
    }

    /**
     * Logs a comprehensive human-readable representation of a StateProof.
     *
     * @param stateProof the state proof to log
     */
    private static void logStateProof(final StateProof stateProof) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n========== StateProof Details ==========\n");

        // Log the signed block proof if present
        if (stateProof.hasSignedBlockProof()) {
            final var signedBlockProof = stateProof.signedBlockProof();
            sb.append("Signed Block Proof:\n");
            sb.append("  Block Signature: ");
            if (signedBlockProof.blockSignature() != null) {
                sb.append(formatBytes(signedBlockProof.blockSignature()));
            } else {
                sb.append("<null>");
            }
            sb.append("\n");
        } else {
            sb.append("Signed Block Proof: <not present>\n");
        }

        // Log the paths
        final var paths = stateProof.paths();
        sb.append("Merkle Paths: ").append(paths.size()).append(" path(s)\n");

        for (int i = 0; i < paths.size(); i++) {
            final var path = paths.get(i);
            sb.append("  Path [").append(i).append("]:\n");
            sb.append("    nextPathIndex: ").append(path.nextPathIndex()).append("\n");

            // Log leaf if present
            if (path.hasBlockItemLeaf() || path.hasStateItemLeaf() || path.hasTimestampLeaf()) {
                sb.append("    Leaf:\n");

                // Log the content type and value based on the oneof field
                if (path.hasTimestampLeaf()) {
                    sb.append("      Content Type: block_consensus_timestamp\n");
                    sb.append("      Value: ")
                            .append(formatBytes(path.timestampLeaf()))
                            .append("\n");
                } else if (path.hasBlockItemLeaf()) {
                    sb.append("      Content Type: block_item\n");
                    sb.append("      Value: ")
                            .append(formatBytes(path.blockItemLeaf()))
                            .append("\n");
                } else if (path.hasStateItemLeaf()) {
                    sb.append("      Content Type: state_item\n");
                    sb.append("      Value: ")
                            .append(formatBytes(path.stateItemLeaf()))
                            .append("\n");
                } else {
                    sb.append("      Content Type: <not set>\n");
                }
            } else if (path.hasHash()) {
                sb.append("    Hash: ").append(formatBytes(path.hash())).append("\n");
            } else {
                sb.append("    Content: <neither leaf nor hash present>\n");
            }

            // Log siblings
            final var siblings = path.siblings();
            sb.append("    Siblings: ").append(siblings.size()).append(" sibling(s)\n");
            for (int j = 0; j < siblings.size(); j++) {
                final var sibling = siblings.get(j);
                sb.append("      Sibling [").append(j).append("]:\n");
                sb.append("        isLeft: ").append(sibling.isLeft()).append("\n");
                sb.append("        hash: ").append(formatBytes(sibling.hash())).append("\n");
            }
        }

        sb.append("========================================\n");
        log.debug("{}", sb.toString());
    }

    /**
     * Formats a Bytes object as a hex string for logging.
     *
     * @param bytes the bytes to format
     * @return a human-readable hex string representation
     */
    private static String formatBytes(final Bytes bytes) {
        if (bytes == null) {
            return "<null>";
        }

        final byte[] array = bytes.toByteArray();
        if (array.length == 0) {
            return "<empty>";
        }

        return bytesToHex(array);
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

    /**
     * Internal record for tracking hash values and their parent path indices during
     * root hash computation.
     */
    private record HashIndexPair(@NonNull byte[] hash, int nextPathIndex) {}
}
