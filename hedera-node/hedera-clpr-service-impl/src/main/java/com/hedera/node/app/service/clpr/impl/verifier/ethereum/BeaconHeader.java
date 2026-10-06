// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The 5-field SSZ {@code BeaconBlockHeader} container. Package-private so tests can build headers
 * directly and exercise {@link #hashTreeRoot()}.
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
