// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class SeiHashingTest {
    @Test
    void rejectsEmptyValidatorSet() {
        assertThatThrownBy(() -> SeiHashing.validatorSetHash(SeiValidatorSet.DEFAULT))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("validator set is empty");
    }

    @Test
    void rejectsMalformedValidators() {
        assertThatThrownBy(() -> SeiHashing.validatorSetHash(SeiValidatorSet.newBuilder()
                        .validators(SeiValidatorEntry.newBuilder()
                                .ed25519PubKey(Bytes.wrap(new byte[31]))
                                .votingPower(1)
                                .build())
                        .build()))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("Ed25519 public key");

        assertThatThrownBy(() -> SeiHashing.validatorSetHash(SeiValidatorSet.newBuilder()
                        .validators(SeiValidatorEntry.newBuilder()
                                .ed25519PubKey(Bytes.wrap(new byte[32]))
                                .votingPower(0)
                                .build())
                        .build()))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("voting power must be positive");
    }

    @Test
    void precommitSignBytesRejectNullInputs() {
        final var blockRef = SeiBlockRef.newBuilder()
                .hash(Bytes.wrap(new byte[32]))
                .partSetTotal(1)
                .partSetHash(Bytes.wrap(new byte[32]))
                .build();
        final var timestamp = Timestamp.newBuilder().seconds(1).build();

        assertThatNullPointerException()
                .isThrownBy(() -> SeiHashing.precommitSignBytes(null, 1, 0, blockRef, timestamp));
        assertThatNullPointerException()
                .isThrownBy(() -> SeiHashing.precommitSignBytes("chain", 1, 0, null, timestamp));
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.precommitSignBytes("chain", 1, 0, blockRef, null));
    }

    @Test
    void rejectsNullHashingInputs() {
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.headerHash(null));
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.validatorSetHash(null));
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.validatorAddress(null));
    }
}
