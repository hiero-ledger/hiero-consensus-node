// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.MerkleProof;
import com.swirlds.state.SiblingHash;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StateProofVerifier}.
 */
class StateProofVerifierTest {

    private static final Bytes TEST_STATE_ITEM_1 = Bytes.wrap("test-state-item-1");
    private static final Bytes TEST_STATE_ITEM_2 = Bytes.wrap("test-state-item-2");
    private static final Bytes TEST_STATE_ITEM_3 = Bytes.wrap("test-state-item-3");
    private static final Bytes TEST_STATE_ITEM_4 = Bytes.wrap("test-state-item-4");
    private static final Hash TEST_HASH = new Hash(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });
    private static final Hash TEST_HASH_2 = new Hash(new byte[] {
        48, 47, 46, 45, 44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21,
        20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    });
    private static final Hash TEST_INNER_HASH = new Hash(new byte[] {
        10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });

    @Test
    @DisplayName("verify() should return true for valid single-proof StateProof")
    void verifySingleProof() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var stateProof =
                StateProofBuilder.newBuilder().addProof(proofs[0]).build();

        assertThat(StateProofVerifier.verify(stateProof)).isTrue();
        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, tree.rootHash()))
                .isTrue();
    }

    @Test
    @DisplayName("verify() should return true for valid multi-proof StateProof")
    void verifyMultipleProofs() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var stateProof = StateProofBuilder.newBuilder()
                .addProof(proofs[0])
                .addProof(proofs[3])
                .build();

        assertThat(StateProofVerifier.verify(stateProof)).isTrue();
        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, tree.rootHash()))
                .isTrue();
    }

    @Test
    @DisplayName("verify() should return false for invalid TSS signature")
    void verifyInvalidSignature() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var wrongSignature = Bytes.wrap("wrong-signature".getBytes());
        final var stateProof = StateProofBuilder.newBuilder()
                .addProof(proofs[0])
                .withTssSignature(wrongSignature)
                .build();

        assertThat(StateProofVerifier.verify(stateProof)).isFalse();
    }

    @Test
    @DisplayName("verify() should return false when signature is missing")
    void verifyMissingSignature() {
        // Given a state proof without signature
        final var merklePath = MerklePath.newBuilder()
                .stateItemLeaf(TEST_STATE_ITEM_1)
                .nextPathIndex(-1)
                .build();

        final var stateProof = StateProof.newBuilder().paths(merklePath).build(); // No signature

        // Then verification should fail
        assertThat(StateProofVerifier.verify(stateProof)).isFalse();
    }

    @Test
    @DisplayName("verify() should return false for tampered leaf data")
    void verifyTamperedLeafData() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var stateProof =
                StateProofBuilder.newBuilder().addProof(proofs[0]).build();

        // Tamper with the leaf data
        final var tamperedPaths = new java.util.ArrayList<>(stateProof.paths());
        final var originalPath = tamperedPaths.get(0);
        final var tamperedPath = originalPath.copyBuilder()
                .stateItemLeaf(Bytes.wrap("tampered-leaf"))
                .build();
        tamperedPaths.set(0, tamperedPath);

        final var tamperedStateProof =
                stateProof.copyBuilder().paths(tamperedPaths).build();

        assertThat(StateProofVerifier.verify(tamperedStateProof)).isFalse();
    }

    @Test
    @DisplayName("verify() should validate end-to-end proof built by StateProofBuilder")
    void verifyEndToEndProof() {
        final var tree = computeFourLeafTree();
        final var proofs = createFourLeafProofs(tree);

        final var builtStateProof = StateProofBuilder.newBuilder()
                .addProof(proofs[1])
                .addProof(proofs[2])
                .build();

        final boolean isValid = StateProofVerifier.verify(builtStateProof);

        assertThat(isValid).isTrue();
        assertThat(StateProofVerifier.verifyRootHashForTest(builtStateProof, tree.rootHash()))
                .isTrue();

        final var stateItems = builtStateProof.paths().stream()
                .filter(MerklePath::hasStateItemLeaf)
                .map(MerklePath::stateItemLeaf)
                .toList();

        assertThat(stateItems).containsExactlyInAnyOrder(TEST_STATE_ITEM_2, TEST_STATE_ITEM_3);
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
        final byte[] leaf0Hash = hashLeaf(TEST_STATE_ITEM_1);
        final byte[] leaf1Hash = hashLeaf(TEST_STATE_ITEM_2);
        final byte[] leaf2Hash = hashLeaf(TEST_STATE_ITEM_3);
        final byte[] leaf3Hash = hashLeaf(TEST_STATE_ITEM_4);
        final byte[] leftHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leaf0Hash, leaf1Hash);
        final byte[] rightHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leaf2Hash, leaf3Hash);
        final byte[] rootHash = HashUtils.joinHashes(HashUtils.newMessageDigest(), leftHash, rightHash);
        return new TreeData(leaf0Hash, leaf1Hash, leaf2Hash, leaf3Hash, leftHash, rightHash, rootHash);
    }

    private static MerkleProof[] createFourLeafProofs(final TreeData tree) {
        final var proof0 = new MerkleProof(
                TEST_STATE_ITEM_1,
                List.of(
                        new SiblingHash(true, new Hash(tree.leaf1Hash())),
                        new SiblingHash(true, new Hash(tree.rightHash()))),
                List.of(new Hash(tree.leaf0Hash()), new Hash(tree.leftHash()), new Hash(tree.rootHash())));

        final var proof1 = new MerkleProof(
                TEST_STATE_ITEM_2,
                List.of(
                        new SiblingHash(false, new Hash(tree.leaf0Hash())),
                        new SiblingHash(true, new Hash(tree.rightHash()))),
                List.of(new Hash(tree.leaf1Hash()), new Hash(tree.leftHash()), new Hash(tree.rootHash())));

        final var proof2 = new MerkleProof(
                TEST_STATE_ITEM_3,
                List.of(
                        new SiblingHash(true, new Hash(tree.leaf3Hash())),
                        new SiblingHash(false, new Hash(tree.leftHash()))),
                List.of(new Hash(tree.leaf2Hash()), new Hash(tree.rightHash()), new Hash(tree.rootHash())));

        final var proof3 = new MerkleProof(
                TEST_STATE_ITEM_4,
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
