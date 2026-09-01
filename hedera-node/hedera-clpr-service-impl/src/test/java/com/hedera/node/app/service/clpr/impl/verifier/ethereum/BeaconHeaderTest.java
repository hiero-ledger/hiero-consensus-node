// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.ZERO_HASH_3;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.merkleizeIndependently;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.uint64LeafLittleEndian;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BeaconHeaderTest {

    @Test
    void allZeroHeaderHashesToDepth3ZeroSubtreeRoot() {
        // hash_tree_root of a zeroed 5-field container padded to 8 leaves is the depth-3 zero subtree.
        byte[] root = new BeaconHeader(0, 0, new byte[32], new byte[32], new byte[32]).hashTreeRoot();
        assertThat(root).isEqualTo(ZERO_HASH_3);
    }

    @Test
    void hashTreeRootMatchesIndependentMerkleization() {
        long slot = 0x0102030405060708L;
        long proposer = 42;
        byte[] parent = deterministicBytes(32, 1);
        byte[] state = deterministicBytes(32, 2);
        byte[] body = deterministicBytes(32, 3);

        // The 5 declared fields, each a 32-byte leaf, padded with 3 zero chunks to a depth-3 tree.
        byte[][] leaves = {
            uint64LeafLittleEndian(slot),
            uint64LeafLittleEndian(proposer),
            parent,
            state,
            body,
            new byte[32],
            new byte[32],
            new byte[32]
        };

        assertThat(new BeaconHeader(slot, proposer, parent, state, body).hashTreeRoot())
                .isEqualTo(merkleizeIndependently(leaves));
    }

    @Test
    void hashTreeRootIsDeterministic() {
        BeaconHeader a =
                new BeaconHeader(7, 9, deterministicBytes(32, 1), deterministicBytes(32, 2), deterministicBytes(32, 3));
        BeaconHeader b =
                new BeaconHeader(7, 9, deterministicBytes(32, 1), deterministicBytes(32, 2), deterministicBytes(32, 3));
        assertThat(a.hashTreeRoot()).isEqualTo(b.hashTreeRoot());
    }

    @Test
    void mutatingAnyFieldChangesTheRoot() {
        // Guards that every field is bound to its own leaf — a dropped or aliased field would let
        // one of these mutations slip through with an unchanged root.
        byte[] baseRoot = new BeaconHeader(
                        1, 2, deterministicBytes(32, 3), deterministicBytes(32, 4), deterministicBytes(32, 5))
                .hashTreeRoot();

        assertThat(new BeaconHeader(
                                99, 2, deterministicBytes(32, 3), deterministicBytes(32, 4), deterministicBytes(32, 5))
                        .hashTreeRoot())
                .as("slot")
                .isNotEqualTo(baseRoot);
        assertThat(new BeaconHeader(
                                1, 99, deterministicBytes(32, 3), deterministicBytes(32, 4), deterministicBytes(32, 5))
                        .hashTreeRoot())
                .as("proposerIndex")
                .isNotEqualTo(baseRoot);
        assertThat(new BeaconHeader(
                                1, 2, deterministicBytes(32, 99), deterministicBytes(32, 4), deterministicBytes(32, 5))
                        .hashTreeRoot())
                .as("parentRoot")
                .isNotEqualTo(baseRoot);
        assertThat(new BeaconHeader(
                                1, 2, deterministicBytes(32, 3), deterministicBytes(32, 99), deterministicBytes(32, 5))
                        .hashTreeRoot())
                .as("stateRoot")
                .isNotEqualTo(baseRoot);
        assertThat(new BeaconHeader(
                                1, 2, deterministicBytes(32, 3), deterministicBytes(32, 4), deterministicBytes(32, 99))
                        .hashTreeRoot())
                .as("bodyRoot")
                .isNotEqualTo(baseRoot);
    }

    @Test
    void fieldOrderingMatters() {
        byte[] parent = deterministicBytes(32, 1);
        byte[] state = deterministicBytes(32, 2);
        byte[] body = deterministicBytes(32, 3);
        byte[] root = new BeaconHeader(0, 0, parent, state, body).hashTreeRoot();

        // parentRoot and stateRoot swapped — distinct leaves, so the root must differ.
        assertThat(new BeaconHeader(0, 0, state, parent, body).hashTreeRoot()).isNotEqualTo(root);
        // slot and proposerIndex carry swapped values — they occupy distinct leaves too.
        byte[] slotProposerRoot = new BeaconHeader(1, 2, parent, state, body).hashTreeRoot();
        assertThat(new BeaconHeader(2, 1, parent, state, body).hashTreeRoot()).isNotEqualTo(slotProposerRoot);
    }

    @Test
    void nonThirtyTwoByteRootFieldThrows() {
        assertThatThrownBy(() -> new BeaconHeader(0, 0, new byte[31], new byte[32], new byte[32]).hashTreeRoot())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentRoot must be 32 bytes, got 31");
        assertThatThrownBy(() -> new BeaconHeader(0, 0, new byte[32], new byte[33], new byte[32]).hashTreeRoot())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateRoot must be 32 bytes, got 33");
        assertThatThrownBy(() -> new BeaconHeader(0, 0, new byte[32], new byte[32], new byte[0]).hashTreeRoot())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bodyRoot must be 32 bytes, got 0");
    }
}
