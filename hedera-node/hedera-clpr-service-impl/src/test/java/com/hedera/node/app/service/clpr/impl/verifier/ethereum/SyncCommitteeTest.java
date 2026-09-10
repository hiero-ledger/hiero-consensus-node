// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.ZERO_HASH_1;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.merkleizeIndependently;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SyncCommitteeTest {

    private static final int SIZE = Ssz.SYNC_COMMITTEE_SIZE; // 512

    @Test
    void allZeroCommitteeRootMatchesIndependentComputation() {
        // pubkeyHash64(zeros48) = sha256(zeros64) = zh1; folding 512 identical leaves applies
        // sha256(x||x) nine times; the committee root pairs that with the aggregate's zh1.
        byte[] level = ZERO_HASH_1;
        for (int i = 0; i < 9; i++) {
            level = sha256(level, level);
        }
        byte[] expected = sha256(level, ZERO_HASH_1);

        assertThat(new SyncCommittee(zeroKeys(), new byte[48]).hashTreeRoot()).isEqualTo(expected);
    }

    @Test
    void distinctCommitteeRootMatchesIndependentComputation() {
        // A non-trivial committee: 512 distinct pubkeys + a distinct aggregate, merkleized
        // independently (pad each key to 64 bytes, sha256, fold the vector, pair with the aggregate).
        byte[][] pubkeys = distinctPubkeys();
        byte[] aggregate = deterministicBytes(48, 0x77);

        byte[][] leaves = new byte[SIZE][];
        for (int i = 0; i < SIZE; i++) {
            leaves[i] = pubkeyLeaf(pubkeys[i]);
        }
        byte[] expected = sha256(merkleizeIndependently(leaves), pubkeyLeaf(aggregate));

        assertThat(new SyncCommittee(pubkeys, aggregate).hashTreeRoot()).isEqualTo(expected);
    }

    @Test
    void aggregatePubkeyIsBoundToTheRoot() {
        byte[][] pubkeys = distinctPubkeys();
        byte[] root = new SyncCommittee(pubkeys, deterministicBytes(48, 0x01)).hashTreeRoot();
        byte[] other = new SyncCommittee(pubkeys, deterministicBytes(48, 0x02)).hashTreeRoot();
        assertThat(other).isNotEqualTo(root);
    }

    @Test
    void memberOrderingMatters() {
        byte[][] pubkeys = distinctPubkeys();
        byte[] aggregate = deterministicBytes(48, 0x77);
        byte[] root = new SyncCommittee(pubkeys, aggregate).hashTreeRoot();

        byte[][] swapped = pubkeys.clone(); // shallow copy; swap the first two members
        byte[] tmp = swapped[0];
        swapped[0] = swapped[1];
        swapped[1] = tmp;
        assertThat(new SyncCommittee(swapped, aggregate).hashTreeRoot()).isNotEqualTo(root);
    }

    @Test
    void wrongPubkeyCountThrows() {
        byte[][] tooFew = new byte[SIZE - 1][];
        Arrays.fill(tooFew, new byte[48]);
        assertThatThrownBy(() -> new SyncCommittee(tooFew, new byte[48]).hashTreeRoot())
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("sync committee must have " + SIZE + " pubkeys");
    }

    @Test
    void wrongPubkeyLengthThrows() {
        byte[][] pubkeys = zeroKeys();
        pubkeys[7] = new byte[47];
        assertThatThrownBy(() -> new SyncCommittee(pubkeys, new byte[48]).hashTreeRoot())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pubkey must be 48 bytes, got 47");
    }

    // ── helpers ──

    private static byte[][] zeroKeys() {
        byte[][] keys = new byte[SIZE][];
        Arrays.fill(keys, new byte[48]);
        return keys;
    }

    private static byte[][] distinctPubkeys() {
        byte[][] keys = new byte[SIZE][];
        for (int i = 0; i < SIZE; i++) {
            keys[i] = deterministicBytes(48, i);
        }
        return keys;
    }

    /** Independent SSZ {@code Bytes48} leaf: the 48 bytes zero-padded to 64 and hashed once. */
    private static byte[] pubkeyLeaf(byte[] pubkey48) {
        byte[] padded = new byte[64];
        System.arraycopy(pubkey48, 0, padded, 0, 48);
        return sha256(padded);
    }
}
