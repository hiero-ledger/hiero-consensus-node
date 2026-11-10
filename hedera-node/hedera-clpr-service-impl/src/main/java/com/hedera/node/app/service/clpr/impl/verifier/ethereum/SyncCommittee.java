// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A sync committee as the verifier trusts it: the SSZ root of its member keys and its aggregate key. The member keys
 * themselves are never stored; a bundle proves each non-signer's key against {@code pubkeysRoot32}.
 *
 * @param pubkeysRoot32     the SSZ {@code hash_tree_root} of the committee's {@code pubkeys} vector
 * @param aggregatePubkey48 the aggregate of all member keys, a compressed G1 point
 */
record SyncCommittee(@NonNull byte[] pubkeysRoot32, @NonNull byte[] aggregatePubkey48) {

    SyncCommittee {
        pubkeysRoot32 = checkedCopy(pubkeysRoot32, 32, "pubkeysRoot");
        aggregatePubkey48 = checkedCopy(aggregatePubkey48, G1Compression.COMPRESSED_LENGTH, "aggregatePubkey");
    }

    /**
     * Builds the committee from its member keys, in committee order.
     *
     * @param pubkeys48         the compressed member keys; their count must be a power of two
     * @param aggregatePubkey48 the compressed aggregate key
     */
    @NonNull
    static SyncCommittee fromPubkeys(@NonNull final byte[][] pubkeys48, @NonNull final byte[] aggregatePubkey48) {
        final byte[][] pubkeyRoots = new byte[pubkeys48.length][];
        for (int i = 0; i < pubkeys48.length; i++) {
            pubkeyRoots[i] = Ssz.pubkeyHash64(pubkeys48[i]);
        }
        return new SyncCommittee(Ssz.merkleize(pubkeyRoots), aggregatePubkey48);
    }

    /**
     * {@code hash_tree_root} of the SSZ {@code SyncCommittee} container: the pubkeys root paired with the aggregate
     * key's root. This is the value the {@code BeaconState.next_sync_committee} branch commits to.
     */
    @NonNull
    byte[] hashTreeRoot() {
        return Ssz.sha256(pubkeysRoot32, Ssz.pubkeyHash64(aggregatePubkey48));
    }

    @Override
    public byte[] pubkeysRoot32() {
        return pubkeysRoot32.clone();
    }

    @Override
    public byte[] aggregatePubkey48() {
        return aggregatePubkey48.clone();
    }
}
