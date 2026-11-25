// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the Gloas generalized indices to the consensus-specs values and their depth/index split. */
class GloasTest {

    @Test
    @DisplayName("given FINALIZED_ROOT, then it is gindex 735: depth 9, index 223")
    void givenFinalizedRoot_thenIsGindex735() {
        assertThat(Gloas.FINALIZED_ROOT.gindex()).isEqualTo(735);
        assertThat(Gloas.FINALIZED_ROOT.depth()).isEqualTo(9);
        assertThat(Gloas.FINALIZED_ROOT.index()).isEqualTo(223);
    }

    @Test
    @DisplayName("given CURRENT_SYNC_COMMITTEE, then it is gindex 2945: depth 11, index 897")
    void givenCurrentSyncCommittee_thenIsGindex2945() {
        assertThat(Gloas.CURRENT_SYNC_COMMITTEE.gindex()).isEqualTo(2945);
        assertThat(Gloas.CURRENT_SYNC_COMMITTEE.depth()).isEqualTo(11);
        assertThat(Gloas.CURRENT_SYNC_COMMITTEE.index()).isEqualTo(897);
    }

    @Test
    @DisplayName("given NEXT_SYNC_COMMITTEE, then it is gindex 2946: depth 11, index 898")
    void givenNextSyncCommittee_thenIsGindex2946() {
        assertThat(Gloas.NEXT_SYNC_COMMITTEE.gindex()).isEqualTo(2946);
        assertThat(Gloas.NEXT_SYNC_COMMITTEE.depth()).isEqualTo(11);
        assertThat(Gloas.NEXT_SYNC_COMMITTEE.index()).isEqualTo(898);
    }

    @Test
    @DisplayName("given EXECUTION_BLOCK_HASH, then it is gindex 2856: depth 11, index 808")
    void givenExecutionBlockHash_thenIsGindex2856() {
        assertThat(Gloas.EXECUTION_BLOCK_HASH.gindex()).isEqualTo(2856);
        assertThat(Gloas.EXECUTION_BLOCK_HASH.depth()).isEqualTo(11);
        assertThat(Gloas.EXECUTION_BLOCK_HASH.index()).isEqualTo(808);
    }
}
