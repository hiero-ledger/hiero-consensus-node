// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The 5-field SSZ {@code BeaconBlockHeader} container: a beacon block summarized by the roots of its parts. Package-private
 * so tests can build headers directly and exercise {@link #hashTreeRoot()}.
 *
 * @param slot          the slot of the block; its period selects the sync committee, and it orders the finalized
 *                      header before the attested one
 * @param proposerIndex the index, in the beacon state's validator registry, of the validator that proposed the block;
 *                      not used by the verifier beyond hashing
 * @param parentRoot32  the {@code hash_tree_root} of the parent (previous) beacon block header; not used by the verifier beyond
 *                      hashing
 * @param stateRoot32   the {@code hash_tree_root} of the beacon state after this block; the finality and
 *                      next-committee branches are proven against it. This is the consensus-layer state, not the EVM
 *                      state root.
 * @param bodyRoot32    the {@code hash_tree_root} of the block body; since Gloas (EIP-7732) the execution block hash
 *                      branch is proven against it
 * @see <a href="https://github.com/ethereum/consensus-specs/blob/master/specs/phase0/beacon-chain.md#beaconblockheader">
 *     BeaconBlockHeader in the consensus specs</a>
 */
record BeaconHeader(
        long slot,
        long proposerIndex,
        @NonNull byte[] parentRoot32,
        @NonNull byte[] stateRoot32,
        @NonNull byte[] bodyRoot32) {

    /**
     * {@code hash_tree_root} of this header: the 5 field leaves padded with 3 zero chunks and
     * merkleized at depth 3. This root is the beacon block root that the sync committee signs
     * (via the signing-root wrapper).
     */
    @NonNull
    byte[] hashTreeRoot() {
        final byte[][] leaves = new byte[8][];
        leaves[0] = Ssz.uint64Leaf(slot);
        leaves[1] = Ssz.uint64Leaf(proposerIndex);
        leaves[2] = checkedCopy(parentRoot32, 32, "parentRoot");
        leaves[3] = checkedCopy(stateRoot32, 32, "stateRoot");
        leaves[4] = checkedCopy(bodyRoot32, 32, "bodyRoot");
        leaves[5] = new byte[32];
        leaves[6] = new byte[32];
        leaves[7] = new byte[32];
        return Ssz.merkleize(leaves);
    }
}
