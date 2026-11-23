// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncCommitteePresetTest {

    @Test
    @DisplayName("given 64-byte bits, then fromParticipationBytes returns the mainnet preset")
    void given64ByteBits_thenFromParticipationBytesReturnsMainnet() {
        assertThat(SyncCommitteePreset.fromParticipationBytes(64)).isEqualTo(SyncCommitteePreset.MAINNET);
    }

    @Test
    @DisplayName("given 4-byte bits, then fromParticipationBytes returns the minimal preset")
    void given4ByteBits_thenFromParticipationBytesReturnsMinimal() {
        assertThat(SyncCommitteePreset.fromParticipationBytes(4)).isEqualTo(SyncCommitteePreset.MINIMAL);
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 3, 5, 32, 63, 65, 128})
    @DisplayName("given an unsupported bits length, then fromParticipationBytes rejects it")
    void givenUnsupportedBitsLength_thenFromParticipationBytesThrows(final int participationBytes) {
        assertThatThrownBy(() -> SyncCommitteePreset.fromParticipationBytes(participationBytes))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("unsupported sync aggregate bits length of " + participationBytes + " bytes");
    }

    @Test
    @DisplayName("given each preset's code, then fromCode returns that preset")
    void givenPresetCode_thenFromCodeRoundTrips() {
        for (final SyncCommitteePreset preset : SyncCommitteePreset.values()) {
            assertThat(SyncCommitteePreset.fromCode(preset.code())).isEqualTo(preset);
        }
    }

    @Test
    @DisplayName("given code 0, then fromCode rejects it")
    void givenCodeZero_thenFromCodeThrows() {
        assertThatThrownBy(() -> SyncCommitteePreset.fromCode(0))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("unknown sync committee preset code 0");
    }

    @Test
    @DisplayName("given the mainnet preset, then its derived sizes match the consensus spec")
    void givenMainnet_thenDerivedSizesMatchSpec() {
        final SyncCommitteePreset mainnet = SyncCommitteePreset.MAINNET;
        assertThat(mainnet.participationBytes()).isEqualTo(64);
        assertThat(mainnet.pubkeysBranchDepth()).isEqualTo(9);
        assertThat(mainnet.periodOf(8191)).isZero();
        assertThat(mainnet.periodOf(8192)).isEqualTo(1);
    }

    @Test
    @DisplayName("given the minimal preset, then its derived sizes match the consensus spec")
    void givenMinimal_thenDerivedSizesMatchSpec() {
        final SyncCommitteePreset minimal = SyncCommitteePreset.MINIMAL;
        assertThat(minimal.participationBytes()).isEqualTo(4);
        assertThat(minimal.pubkeysBranchDepth()).isEqualTo(5);
        assertThat(minimal.periodOf(63)).isZero();
        assertThat(minimal.periodOf(64)).isEqualTo(1);
    }

    @Test
    @DisplayName("given a slot with the top bit set, then periodOf reads it as unsigned")
    void givenSlotWithTopBitSet_thenPeriodOfIsUnsigned() {
        assertThat(SyncCommitteePreset.MAINNET.periodOf(-1L)).isEqualTo(Long.divideUnsigned(-1L, 8192));
    }

    @Test
    @DisplayName("given participant counts around 2/3, then isSupermajority splits at 342/512 and 22/32")
    void givenParticipantCounts_thenSupermajorityThresholds() {
        assertThat(SyncCommitteePreset.MAINNET.isSupermajority(341)).isFalse();
        assertThat(SyncCommitteePreset.MAINNET.isSupermajority(342)).isTrue();
        assertThat(SyncCommitteePreset.MINIMAL.isSupermajority(21)).isFalse();
        assertThat(SyncCommitteePreset.MINIMAL.isSupermajority(22)).isTrue();
    }
}
