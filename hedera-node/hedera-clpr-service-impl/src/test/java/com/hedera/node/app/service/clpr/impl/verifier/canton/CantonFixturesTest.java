// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CantonFixturesTest {
    @Test
    void loadsRealVerdictFixtures() {
        assertThat(CantonFixtures.verdictTypedContent()).isNotEmpty();
        assertThat(CantonFixtures.verdictSequencedContent()).isNotEmpty();
        assertThat(CantonFixtures.verdictMediatorSig()).hasSize(64);
        assertThat(CantonFixtures.verdictMediatorKey()).hasSize(32);
        assertThat(CantonFixtures.verdictSequencerSig()).hasSize(64);
        assertThat(CantonFixtures.verdictSequencerKey()).hasSize(32);
        assertThat(CantonFixtures.updateMultihash()).hasSize(34);
        assertThat(CantonFixtures.updateMultihashSha256()).hasSize(32);
    }

    @Test
    void updateMultihashDigestIsSha256() throws Exception {
        final var digest = java.security.MessageDigest.getInstance("SHA-256").digest(CantonFixtures.updateMultihash());
        assertThat(digest).isEqualTo(CantonFixtures.updateMultihashSha256());
        assertThat(CantonFixtures.updateMultihash()[0]).isEqualTo((byte) 0x12);
    }

    @Test
    void loadsTopologyFixtures() {
        assertThat(CantonFixtures.topologyOwnerSet()).isNotEmpty();
        assertThat(CantonFixtures.topologyMediatorSet()).isNotEmpty();
        assertThat(CantonFixtures.topologyRotations()).isNotEmpty();
        assertThat(CantonFixtures.topologyTxLargest()).isNotEmpty();
        assertThat(CantonFixtures.lsuAnnouncement()).isNotEmpty();
        assertThat(CantonFixtures.lsuOwnerSet()).isNotEmpty();
    }

    @Test
    void loadsJsonBundle() {
        assertThat(CantonFixtures.localnetExampleProofBundleJson()).contains("1220aec33071");
    }

    @Test
    void missingFixtureFails() {
        assertThatThrownBy(() -> CantonFixtures.load("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
