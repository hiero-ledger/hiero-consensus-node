// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.ZERO_HASH_1;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.merkleizeIndependently;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.Committee;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncCommitteeTest {

    private static final int MAINNET_SIZE = 512;

    @Test
    @DisplayName("given 512 zero keys, then fromPubkeys computes the independently merkleized pubkeys root")
    void givenZeroKeys_thenFromPubkeysMatchesIndependentRoot() {
        // Every leaf is sha256(48 zero bytes ‖ 16 zero bytes) = ZERO_HASH_1.
        final byte[][] leaves = new byte[MAINNET_SIZE][];
        Arrays.fill(leaves, ZERO_HASH_1);

        final SyncCommittee committee = SyncCommittee.fromPubkeys(keys(MAINNET_SIZE, 0, true), new byte[48]);

        assertThat(committee.pubkeysRoot32()).isEqualTo(merkleizeIndependently(leaves));
    }

    @Test
    @DisplayName("given distinct keys, then fromPubkeys computes the independently merkleized pubkeys root")
    void givenDistinctKeys_thenFromPubkeysMatchesIndependentRoot() {
        final byte[][] pubkeys = keys(32, 0x10, false);
        final byte[][] leaves = new byte[pubkeys.length][];
        for (int i = 0; i < pubkeys.length; i++) {
            leaves[i] = sha256(pubkeys[i], new byte[16]);
        }

        final SyncCommittee committee = SyncCommittee.fromPubkeys(pubkeys, deterministicBytes(48, 0x01));

        assertThat(committee.pubkeysRoot32()).isEqualTo(merkleizeIndependently(leaves));
    }

    @Test
    @DisplayName("given a committee, then hashTreeRoot pairs the pubkeys root with the aggregate's leaf")
    void givenCommittee_thenHashTreeRootPairsPubkeysRootAndAggregateLeaf() {
        final byte[] pubkeysRoot = deterministicBytes(32, 0x21);
        final byte[] aggregate = deterministicBytes(48, 0x22);

        final byte[] root = new SyncCommittee(pubkeysRoot, aggregate).hashTreeRoot();

        assertThat(root).isEqualTo(sha256(pubkeysRoot, sha256(aggregate, new byte[16])));
    }

    @Test
    @DisplayName("given swapped members, then fromPubkeys computes a different pubkeys root")
    void givenSwappedMembers_thenPubkeysRootChanges() {
        final byte[][] pubkeys = keys(32, 0x30, false);
        final byte[] root = SyncCommittee.fromPubkeys(pubkeys, new byte[48]).pubkeysRoot32();
        final byte[][] swapped = pubkeys.clone();
        swapped[0] = pubkeys[1];
        swapped[1] = pubkeys[0];

        assertThat(SyncCommittee.fromPubkeys(swapped, new byte[48]).pubkeysRoot32())
                .isNotEqualTo(root);
    }

    @Test
    @DisplayName("given a committee, then decompressAggregatePubkey returns the aggregate's EIP-2537 encoding")
    void givenCommittee_thenDecompressAggregatePubkeyReturnsUncompressedAggregate() {
        final Committee keys = Committee.of(SyncCommitteePreset.MINIMAL, 1);

        final SyncCommittee committee = new SyncCommittee(keys.pubkeysRoot, keys.compressedAggregate);

        assertThat(committee.decompressAggregatePubkey()).isEqualTo(keys.uncompressedAggregate);
    }

    @Test
    @DisplayName("given a 96-byte aggregate, then the constructor rejects it")
    void givenUncompressedAggregate_thenConstructorThrows() {
        assertThatThrownBy(() -> new SyncCommittee(new byte[32], new byte[96]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregatePubkey must be 48 bytes, got 96");
    }

    @Test
    @DisplayName("given a committee, then its accessors return copies")
    void givenCommittee_thenAccessorsReturnCopies() {
        final SyncCommittee committee = new SyncCommittee(deterministicBytes(32, 1), deterministicBytes(48, 2));

        committee.pubkeysRoot32()[0] ^= 1;
        committee.aggregatePubkey48()[0] ^= 1;

        assertThat(committee.pubkeysRoot32()).isEqualTo(deterministicBytes(32, 1));
        assertThat(committee.aggregatePubkey48()).isEqualTo(deterministicBytes(48, 2));
    }

    private static byte[][] keys(final int count, final int seed, final boolean zero) {
        final byte[][] keys = new byte[count][];
        for (int i = 0; i < count; i++) {
            keys[i] = zero ? new byte[48] : deterministicBytes(48, seed + i);
        }
        return keys;
    }
}
