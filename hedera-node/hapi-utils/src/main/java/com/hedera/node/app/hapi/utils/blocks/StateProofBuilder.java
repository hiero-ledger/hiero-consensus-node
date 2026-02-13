// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.binary.MerkleProof;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds an aggregated {@link StateProof} from multiple single-leaf proofs. Internally maintains a tree of
 * {@link MerklePathBuilder} instances that share suffixes to minimise duplication when serialised.
 */
public final class StateProofBuilder {

    private MPTreeNode root;
    private Bytes explicitSignature;

    private StateProofBuilder() {}

    public static StateProofBuilder newBuilder() {
        return new StateProofBuilder();
    }

    /**
     * Adds a State API {@link MerkleProof} to the aggregated proof.
     *
     * @param merkleProof the proof to add
     * @return this builder
     */
    public StateProofBuilder addProof(@NonNull final MerkleProof merkleProof) {
        requireNonNull(merkleProof, "merkleProof must not be null");
        return addMerklePath(MerklePathBuilder.fromStateApi(merkleProof));
    }

    /**
     * Adds a {@link MerklePathBuilder} directly to the aggregation tree.
     *
     * @param path a path builder representing a single-leaf proof
     * @return this builder
     */
    public StateProofBuilder addMerklePath(@NonNull final MerklePathBuilder path) {
        requireNonNull(path, "path must not be null");
        if (root == null) {
            root = new MPTreeNode(path);
        } else {
            root.merge(path);
        }
        return this;
    }

    /**
     * Extends the current aggregated proof upward by appending siblings above the existing root.
     *
     * @param extension path builder containing only sibling nodes ordered from the previous root upward
     * @return this builder
     */
    public StateProofBuilder extendRoot(@NonNull final MerklePathBuilder extension) {
        requireNonNull(extension, "extension must not be null");
        if (root == null) {
            throw new IllegalStateException("Cannot extend root: no existing root path");
        }
        if (extension.hasLeaf() || extension.getHash() != null) {
            throw new IllegalArgumentException("Root extension must not contain a leaf or hash");
        }
        final var siblings = extension.getSiblingNodes();
        if (siblings.isEmpty()) {
            return this;
        }
        root.merklePathBuilder.appendSiblingNodes(siblings);
        root.rootHash = root.merklePathBuilder.getRootHash();
        return this;
    }

    /**
     * Optionally overrides the TSS signature embedded in the built {@link StateProof}. When not provided, the
     * computed root hash is used as the signature payload for ease of testing.
     *
     * @param signature TSS signature bytes
     * @return this builder
     */
    public StateProofBuilder withTssSignature(@NonNull final Bytes signature) {
        this.explicitSignature = requireNonNull(signature, "signature must not be null");
        return this;
    }

    /**
     * @return the aggregated root hash for the current set of paths, or {@code null} if no paths added yet
     */
    public byte[] aggregatedRootHash() {
        return root == null ? null : root.getRootHash();
    }

    /**
     * Builds the aggregated {@link StateProof}.
     *
     * @return immutable state proof
     */
    public StateProof build() {
        if (root == null) {
            throw new IllegalStateException("At least one path must be added before building a state proof");
        }
        final var paths = buildMerklePaths(root);
        final var signatureBytes = explicitSignature != null ? explicitSignature : Bytes.wrap(root.getRootHash());
        final var tssProof =
                TssSignedBlockProof.newBuilder().blockSignature(signatureBytes).build();
        return StateProof.newBuilder().paths(paths).signedBlockProof(tssProof).build();
    }

    private static List<MerklePath> buildMerklePaths(final MPTreeNode root) {
        final var pathBuilders = new ArrayList<MerklePathBuilder>();
        root.buildMerklePaths(pathBuilders).setNextPathIndex(-1);
        final var result = new ArrayList<MerklePath>(pathBuilders.size());
        for (final var builder : pathBuilders) {
            result.add(builder.build());
        }
        return result;
    }

    /**
     * Internal merge tree node mirroring the prototype implementation. Each node owns a {@link MerklePathBuilder}
     * describing the shared suffix for the subtree beneath it.
     */
    static final class MPTreeNode {
        private MerklePathBuilder merklePathBuilder;
        private MPTreeNode leftBranch;
        private MPTreeNode rightBranch;
        private byte[] rootHash;

        MPTreeNode(@NonNull final MerklePathBuilder merklePathBuilder) {
            this.merklePathBuilder = requireNonNull(merklePathBuilder, "merklePathBuilder must not be null");
            this.rootHash = requireNonNull(merklePathBuilder.getRootHash(), "path root hash must not be null");
        }

        private MPTreeNode(
                @NonNull final MerklePathBuilder parent, final MPTreeNode leftBranch, final MPTreeNode rightBranch) {
            this.merklePathBuilder = requireNonNull(parent, "parent must not be null");
            this.leftBranch = leftBranch;
            this.rightBranch = rightBranch;
            this.rootHash = requireNonNull(parent.getRootHash(), "parent root hash must not be null");
        }

        byte[] getRootHash() {
            return rootHash;
        }

        /**
         * Merge a new path into the subtree rooted at this node. The merge walks from the root toward the
         * leaves, comparing sibling hashes, until it finds the first divergence. If the paths diverge
         * immediately below this node we split the node into two children while retaining the shared suffix
         * here. Otherwise the algorithm recurses into the child subtree whose cached root hash matches the
         * pruned incoming path. Any mismatch indicates an incompatible proof.
         *
         * <p><b>Algorithm Overview:</b>
         * <ol>
         *   <li>Check if new path has same root hash; throw if not</li>
         *   <li>Find match length: longest common suffix of sibling nodes</li>
         *   <li>If match length &lt; current siblings size: branch at divergence point
         *     <ul>
         *       <li>Compute expected inner hash from pruned children</li>
         *       <li>Validate against cached inner hash</li>
         *       <li>Create new child nodes for divergent paths</li>
         *     </ul>
         *   </li>
         *   <li>Else: recurse into matching child subtree or ignore if identical</li>
         * </ol>
         *
         * <p>This ensures proofs are cryptographically consistent and minimizes duplication.
         */
        void merge(@NonNull final MerklePathBuilder newPath) {
            requireNonNull(newPath, "newPath must not be null");
            if (!hashesEqual(rootHash, newPath.getRootHash())) {
                throw new IllegalStateException("Cannot merge paths with different root hashes");
            }
            final int matchLength = matchTreePath(newPath);
            if (matchLength < merklePathBuilder.getSiblingNodes().size()) {
                branchInThisNode(matchLength, newPath);
            } else {
                if (leftBranch == null && rightBranch == null) {
                    // The new path is identical to this node's path, so we can ignore it.
                    return;
                }
                final var prunedPath = newPath.pruneFromRoot(matchLength + 1);
                final byte[] prunedRootHash = prunedPath.getRootHash();
                if (leftBranch != null && hashesEqual(prunedRootHash, leftBranch.getRootHash())) {
                    leftBranch.merge(prunedPath);
                } else if (rightBranch != null && hashesEqual(prunedRootHash, rightBranch.getRootHash())) {
                    rightBranch.merge(prunedPath);
                } else {
                    throw new IllegalStateException("Cannot merge pruned path, does not match either branch");
                }
            }
        }

        /**
         * Split this node at the first branching point between the existing path and {@code newPath}.
         * The longest common suffix is retained at this node, while the unmatched prefixes become newly
         * created left/right child subtrees. Cached inner-node hashes are cross-checked to ensure the
         * two proofs truly share the claimed suffix.
         */
        private void branchInThisNode(final int matchLength, final MerklePathBuilder newPath) {
            final var treeSiblings = merklePathBuilder.getSiblingNodes();
            final var newPathSiblings = newPath.getSiblingNodes();
            final int treeBranchIndex = treeSiblings.size() - 1 - matchLength;
            final int newPathBranchIndex = newPathSiblings.size() - 1 - matchLength;
            final var treeBranchSibling = treeSiblings.get(treeBranchIndex);
            final var newPathBranchSibling = newPathSiblings.get(newPathBranchIndex);

            final var treePrefix = merklePathBuilder.prefixMerklePathBuilder(matchLength);
            final var prunedNewPath = newPath.pruneFromRoot(matchLength + 1);
            final var prunedExistingPath = merklePathBuilder.pruneFromRoot(matchLength + 1);

            final byte[] expectedInnerNodeHash = HashUtils.joinHashes(
                    HashUtils.newMessageDigest(), prunedExistingPath.getRootHash(), prunedNewPath.getRootHash());

            if (!hashesEqual(expectedInnerNodeHash, merklePathBuilder.getInnerNodeHash(treeBranchIndex + 1))) {
                throw new IllegalStateException("Incompatible inner node hashes for branching at match point");
            }

            final var prunedExistingNode = new MPTreeNode(prunedExistingPath, leftBranch, rightBranch);
            final var prunedNewNode = new MPTreeNode(prunedNewPath);

            this.merklePathBuilder = treePrefix;
            final boolean treePathIsLeftBranch = !treeBranchSibling.isLeft();
            if (treePathIsLeftBranch) {
                leftBranch = prunedExistingNode;
                rightBranch = prunedNewNode;
            } else {
                leftBranch = prunedNewNode;
                rightBranch = prunedExistingNode;
            }
        }

        /**
         * Finds the length of the longest common suffix of sibling nodes between this path and {@code newPath}.
         * Siblings are compared from the root end (highest index) towards the leaf.
         *
         * <p><b>Time Complexity:</b> O(min(len1, len2)) where len is the number of siblings.
         * Since Merkle path lengths are typically small (&lt; 32), this is effectively O(1).
         *
         * @param newPath the path to compare against
         * @return the number of matching siblings from the root
         */
        private int matchTreePath(final MerklePathBuilder newPath) {
            final var treeSiblings = merklePathBuilder.getSiblingNodes();
            final var newPathSiblings = newPath.getSiblingNodes();
            int treeIndex = treeSiblings.size() - 1;
            int newIndex = newPathSiblings.size() - 1;
            int matchLength = 0;
            while (treeIndex >= 0 && newIndex >= 0) {
                if (!treeSiblings.get(treeIndex).equals(newPathSiblings.get(newIndex))) {
                    break;
                }
                treeIndex--;
                newIndex--;
                matchLength++;
            }
            return matchLength;
        }

        MPTreeNode buildMerklePaths(final ArrayList<MerklePathBuilder> pathList) {
            final MPTreeNode leftTree = (leftBranch == null) ? null : leftBranch.buildMerklePaths(pathList);
            final MPTreeNode rightTree = (rightBranch == null) ? null : rightBranch.buildMerklePaths(pathList);
            final int selfIndex = pathList.size();
            pathList.add(merklePathBuilder);
            merklePathBuilder.setNextPathIndex(-1);
            if (leftTree != null) {
                leftTree.setNextPathIndex(selfIndex);
            }
            if (rightTree != null) {
                rightTree.setNextPathIndex(selfIndex);
            }
            return this;
        }

        void setNextPathIndex(final int index) {
            merklePathBuilder.setNextPathIndex(index);
        }

        private static boolean hashesEqual(final byte[] a, final byte[] b) {
            return Arrays.equals(a, b);
        }
    }
}
