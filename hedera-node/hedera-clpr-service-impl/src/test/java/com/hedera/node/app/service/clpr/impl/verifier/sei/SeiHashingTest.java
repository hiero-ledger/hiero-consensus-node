// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.cryptography.libsodium.Libsodium;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeiHashingTest {
    /// libsodium is used for Ed25519 signatures.
    private static final Libsodium LIBSODIUM = Libsodium.getInstance();

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
    void ed25519InvalidRandomInputsReturnFalse() {
        assertThat(SeiHashing.verifyEd25519(new byte[32], new byte[] {1}, new byte[64]))
                .isFalse();
    }

    @Test
    void verifiesRealEd25519Signature() {
        // NOTE: this test has previously used an Ed25519Signer from Bouncy Castle, and when the production code
        // has been migrated to use Libsodium, it was confirmed that a signature produced by Bouncy Castle
        // verifies successfully using the new production code with Libsodium. Since we're reducing our dependency
        // on Bouncy Castle, now we've migrated this test to use Libsodium to produce Ed25519 signatures as well.

        final byte[] publicKey = new byte[32];
        final byte[] privateKey = new byte[64];
        assertEquals(
                0, LIBSODIUM.cryptoSignKeypair(MemorySegment.ofArray(publicKey), MemorySegment.ofArray(privateKey)));

        final byte[] message = "message".getBytes(StandardCharsets.UTF_8);
        final byte[] signature = new byte[64];
        final long[] sigLen = new long[] {signature.length};
        assertEquals(
                0,
                LIBSODIUM.cryptoSignDetached(
                        MemorySegment.ofArray(signature),
                        MemorySegment.ofArray(sigLen),
                        MemorySegment.ofArray(message),
                        message.length,
                        MemorySegment.ofArray(privateKey)));

        assertThat(SeiHashing.verifyEd25519(publicKey, message, signature)).isTrue();
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
