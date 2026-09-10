// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
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
    void malformedEd25519InputsReturnFalse() {
        assertThat(SeiHashing.verifyEd25519(new byte[31], new byte[] {1}, new byte[64]))
                .isFalse();
        assertThat(SeiHashing.verifyEd25519(new byte[32], new byte[] {1}, new byte[63]))
                .isFalse();
    }

    @Test
    void ed25519RuntimeErrorsReturnFalse() {
        try (final var ignored =
                mockConstruction(Ed25519Signer.class, (signer, context) -> doThrow(new RuntimeException("boom"))
                        .when(signer)
                        .init(eq(false), any(CipherParameters.class)))) {
            assertThat(SeiHashing.verifyEd25519(new byte[32], new byte[] {1}, new byte[64]))
                    .isFalse();
        }
    }

    @Test
    void verifiesRealEd25519Signature() {
        final byte[] seed = SeiMerkle.sha256("seed".getBytes(StandardCharsets.UTF_8));
        final var privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        final byte[] publicKey = privateKey.generatePublicKey().getEncoded();
        final byte[] message = "message".getBytes(StandardCharsets.UTF_8);
        final var signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(message, 0, message.length);

        assertThat(SeiHashing.verifyEd25519(publicKey, message, signer.generateSignature()))
                .isTrue();
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
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.verifyEd25519(null, new byte[0], new byte[64]));
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.verifyEd25519(new byte[32], null, new byte[64]));
        assertThatNullPointerException().isThrownBy(() -> SeiHashing.verifyEd25519(new byte[32], new byte[0], null));
    }
}
