// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.Committee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncCommitteeTest {

    @Test
    @DisplayName("given a committee, then hashTreeRoot pairs the pubkeys root with the aggregate's leaf")
    void givenCommittee_thenHashTreeRootPairsPubkeysRootAndAggregateLeaf() {
        final byte[] pubkeysRoot = deterministicBytes(32, 0x21);
        final byte[] aggregate = deterministicBytes(48, 0x22);

        final byte[] root = new SyncCommittee(pubkeysRoot, aggregate).hashTreeRoot();

        assertThat(root).isEqualTo(sha256(pubkeysRoot, sha256(aggregate, new byte[16])));
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
}
