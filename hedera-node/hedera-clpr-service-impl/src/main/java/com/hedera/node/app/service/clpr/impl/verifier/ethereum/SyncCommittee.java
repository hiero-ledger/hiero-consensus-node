// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * The SSZ {@code SyncCommittee} container: the 512 member pubkeys plus their precomputed
 * aggregate. Package-private so tests can build committees directly and exercise
 * {@link #hashTreeRoot()}.
 */
record SyncCommittee(@NonNull byte[][] pubkeys, @NonNull byte[] aggregatePubkey48) {

    /**
     * {@code hash_tree_root} of this committee: the root of the 512-pubkey vector paired with the
     * aggregate pubkey's root. This is the value pinned by the trust anchor and by the
     * {@code BeaconState.current_sync_committee} branch.
     */
    @NonNull
    byte[] hashTreeRoot() {
        if (pubkeys.length != Ssz.SYNC_COMMITTEE_SIZE) {
            throw EthProofs.fail("sync committee must have " + Ssz.SYNC_COMMITTEE_SIZE + " pubkeys");
        }
        final byte[][] pubkeyRoots = new byte[Ssz.SYNC_COMMITTEE_SIZE][];
        for (int i = 0; i < Ssz.SYNC_COMMITTEE_SIZE; i++) {
            pubkeyRoots[i] = Ssz.pubkeyHash64(pubkeys[i]);
        }
        final byte[] pubkeysRoot = Ssz.merkleize(pubkeyRoots);
        return Ssz.sha256(pubkeysRoot, Ssz.pubkeyHash64(aggregatePubkey48));
    }

    /** RLP-encodes this committee as {@code [pubkeys[], aggregatePubkey]} — inverse of the decoder. */
    @NonNull
    byte[] encode() {
        if (pubkeys.length != Ssz.SYNC_COMMITTEE_SIZE) {
            throw EthProofs.fail("sync committee must have " + Ssz.SYNC_COMMITTEE_SIZE + " pubkeys");
        }
        final List<byte[]> encodedKeys = new ArrayList<>(Ssz.SYNC_COMMITTEE_SIZE);
        for (int i = 0; i < Ssz.SYNC_COMMITTEE_SIZE; i++) {
            encodedKeys.add(Rlp.encodeBytes(
                    checkedCopy(pubkeys[i], Ssz.BLS_PUBKEY_LENGTH, "syncCommittee.pubkeys[" + i + "]")));
        }
        return Rlp.encodeList(List.of(
                Rlp.encodeList(encodedKeys),
                Rlp.encodeBytes(
                        checkedCopy(aggregatePubkey48, Ssz.BLS_PUBKEY_LENGTH, "syncCommittee.aggregatePubkey"))));
    }
}
