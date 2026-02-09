// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.MerkleProof;
import com.swirlds.state.SiblingHash;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StateProofBuilder}.
 */
class StateProofBuilderTest {

    private static final Bytes LEAF_ITEM_0 = Bytes.wrap("leaf-0");
    private static final Bytes LEAF_ITEM_1 = Bytes.wrap("leaf-1");
    private static final Bytes LEAF_ITEM_2 = Bytes.wrap("leaf-2");
    private static final Bytes LEAF_ITEM_3 = Bytes.wrap("leaf-3");
    private static final Bytes CUSTOM_SIGNATURE = Bytes.wrap("explicit-signature".getBytes());

    @Test
    @DisplayName("addProof should reject null inputs")
    void addProofRejectsNull() {
        final var builder = StateProofBuilder.newBuilder();
        assertThatThrownBy(() -> builder.addProof(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("merkleProof must not be null");
    }

    @Test
    @DisplayName("build aggregates multiple proofs and matches manual root computation")
    void aggregatedRootMatchesManualComputation() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var builder = StateProofBuilder.newBuilder().addProof(proofs[0]).addProof(proofs[3]);
        final StateProof stateProof = builder.build();

        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, tree.rootHash()))
                .isTrue();
    }

    @Test
    @DisplayName("extendRoot lifts the proof one level and recomputes root hash")
    void extendRootRecomputesRootHash() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var builder = StateProofBuilder.newBuilder()
                .addProof(proofs[0])
                .addProof(proofs[1])
                .addProof(proofs[3]);

        final var extensionSibling =
                HashUtils.computeStateItemLeafHash(HashUtils.newMessageDigest(), Bytes.wrap("extension"));
        final var extensionPath = new MerklePathBuilder()
                .appendSiblingNode(com.hedera.hapi.block.stream.SiblingNode.newBuilder()
                        .isLeft(true)
                        .hash(Bytes.wrap(extensionSibling))
                        .build());

        builder.extendRoot(extensionPath);

        final var extendedRoot = HashUtils.joinHashes(HashUtils.newMessageDigest(), extensionSibling, tree.rootHash());
        final var stateProof = builder.build();
        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, extendedRoot))
                .isTrue();
    }

    @Test
    @DisplayName("explicit signature overrides default root-hash signature")
    void explicitSignatureOverridesDefault() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var builder = StateProofBuilder.newBuilder().addProof(proofs[2]).withTssSignature(CUSTOM_SIGNATURE);

        final var stateProof = builder.build();

        assertThat(stateProof.signedBlockProof())
                .isEqualTo(TssSignedBlockProof.newBuilder()
                        .blockSignature(CUSTOM_SIGNATURE)
                        .build());
        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, tree.rootHash()))
                .isTrue();
    }

    @Test
    @DisplayName("builder emits parent-linked paths for complex merges")
    void buildProducesLinkedParentIndices() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var stateProof = StateProofBuilder.newBuilder()
                .addProof(proofs[0])
                .addProof(proofs[1])
                .addProof(proofs[2])
                .build();

        assertThat(stateProof.paths()).isNotEmpty();
        final boolean hasParentLink = stateProof.paths().stream().anyMatch(path -> path.nextPathIndex() != -1);
        assertThat(hasParentLink).isTrue();
    }

    @Test
    @DisplayName("adding identical proofs is treated as a no-op")
    void duplicateProofsAreIgnored() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var builder = StateProofBuilder.newBuilder().addProof(proofs[0]).addProof(proofs[0]);

        final var stateProof = builder.build();

        assertThat(stateProof.paths()).hasSize(1);
        assertThat(builder.aggregatedRootHash()).isEqualTo(tree.rootHash());
        assertThat(stateProof.signedBlockProof().blockSignature()).isEqualTo(Bytes.wrap(tree.rootHash()));
    }

    @Test
    @DisplayName("adding proofs with mismatched root hashes throws")
    void addProofWithDifferentRootThrows() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final byte[] unrelatedRoot =
                HashUtils.computeStateItemLeafHash(HashUtils.newMessageDigest(), Bytes.wrap("different-root"));
        final var mismatchedProof =
                new MerkleProof(Bytes.wrap("other-leaf"), List.of(), List.of(new Hash(unrelatedRoot)));

        final var builder = StateProofBuilder.newBuilder().addProof(proofs[0]);
        assertThatThrownBy(() -> builder.addProof(mismatchedProof))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different root hashes");
    }

    @Test
    @DisplayName("adding proofs with mismatched inner nodes throws")
    void addProofWithMismatchedInnerNodesThrows() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        // Create a proof that has the same root, but different inner nodes
        final var mismatchedInnerProof = new MerkleProof(
                LEAF_ITEM_0,
                List.of(
                        new SiblingHash(true, new Hash(tree.leaf1Hash())),
                        new SiblingHash(true, new Hash(tree.rightHash()))),
                new java.util.ArrayList<>(
                        List.of(new Hash(tree.leaf0Hash()), new Hash(tree.leftHash()), new Hash(tree.rootHash()))));
        // Tamper with the inner node hash
        mismatchedInnerProof.innerParentHashes().set(1, new Hash(new byte[48]));

        final var builder = StateProofBuilder.newBuilder().addProof(proofs[1]);
        assertThatThrownBy(() -> builder.addProof(mismatchedInnerProof))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible inner node hashes for branching at match point");
    }

    @Test
    @DisplayName("build throws if no proofs are added")
    void buildThrowsIfNoProofsAdded() {
        final var builder = StateProofBuilder.newBuilder();
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one path must be added before building a state proof");
    }

    private record TreeData(
            byte[] leaf0Hash,
            byte[] leaf1Hash,
            byte[] leaf2Hash,
            byte[] leaf3Hash,
            byte[] leftHash,
            byte[] rightHash,
            byte[] rootHash) {}

    private static TreeData computeFourLeafTree() {
        final byte[] leaf0Hash = hashLeaf(LEAF_ITEM_0);
        final byte[] leaf1Hash = hashLeaf(LEAF_ITEM_1);
        final byte[] leaf2Hash = hashLeaf(LEAF_ITEM_2);
        final byte[] leaf3Hash = hashLeaf(LEAF_ITEM_3);
        final byte[] leftHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leaf0Hash, leaf1Hash);
        final byte[] rightHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leaf2Hash, leaf3Hash);
        final byte[] rootHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leftHash, rightHash);
        return new TreeData(leaf0Hash, leaf1Hash, leaf2Hash, leaf3Hash, leftHash, rightHash, rootHash);
    }

    private static MerkleProof[] createFourLeafProofs(final TreeData tree) {
        final var proof0 = new MerkleProof(
                LEAF_ITEM_0,
                List.of(
                        new SiblingHash(true, new Hash(tree.leaf1Hash())),
                        new SiblingHash(true, new Hash(tree.rightHash()))),
                List.of(new Hash(tree.leaf0Hash()), new Hash(tree.leftHash()), new Hash(tree.rootHash())));

        final var proof1 = new MerkleProof(
                LEAF_ITEM_1,
                List.of(
                        new SiblingHash(false, new Hash(tree.leaf0Hash())),
                        new SiblingHash(true, new Hash(tree.rightHash()))),
                List.of(new Hash(tree.leaf1Hash()), new Hash(tree.leftHash()), new Hash(tree.rootHash())));

        final var proof2 = new MerkleProof(
                LEAF_ITEM_2,
                List.of(
                        new SiblingHash(true, new Hash(tree.leaf3Hash())),
                        new SiblingHash(false, new Hash(tree.leftHash()))),
                List.of(new Hash(tree.leaf2Hash()), new Hash(tree.rightHash()), new Hash(tree.rootHash())));

        final var proof3 = new MerkleProof(
                LEAF_ITEM_3,
                List.of(
                        new SiblingHash(false, new Hash(tree.leaf2Hash())),
                        new SiblingHash(false, new Hash(tree.leftHash()))),
                List.of(new Hash(tree.leaf3Hash()), new Hash(tree.rightHash()), new Hash(tree.rootHash())));

        return new MerkleProof[] {proof0, proof1, proof2, proof3};
    }

    private static byte[] hashLeaf(final Bytes item) {
        return HashUtils.computeStateItemLeafHash(HashUtils.newMessageDigest(), item);
    }
}
