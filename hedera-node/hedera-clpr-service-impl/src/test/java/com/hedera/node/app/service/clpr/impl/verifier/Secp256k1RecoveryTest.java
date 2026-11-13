// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.sun.jna.ptr.IntByReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.Test;

class Secp256k1RecoveryTest {
    private static final byte[] PRIV = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };
    private static final byte[] ADDR = EthSigsUtils.recoverAddressFromPrivateKey(PRIV);

    @Test
    void digestNot32Bytes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(new byte[31], new byte[65]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest must be 32 bytes");
    }

    @Test
    void signatureNot65Bytes_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(new byte[32], new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature must be 65 bytes");
    }

    @Test
    void nullInputs_throwNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> Secp256k1Recovery.recoverAddress(null, new byte[65]));
        assertThatNullPointerException().isThrownBy(() -> Secp256k1Recovery.recoverAddress(new byte[32], null));
    }

    @Test
    void v27AndV28NormalizedCorrectly() {
        final byte[] hash = MiscCryptoUtils.keccak256DigestOf(new byte[] {0x01});
        final byte[] sig = sign(hash, PRIV);

        final byte[] sigWithOffset = Arrays.copyOf(sig, 65);
        sigWithOffset[64] = (byte) (sig[64] + 27);

        assertThat(Secp256k1Recovery.recoverAddress(hash, sigWithOffset)).isEqualTo(ADDR);
    }

    @Test
    void vEquals2_throwsIllegalArgumentException() {
        final byte[] hash = MiscCryptoUtils.keccak256DigestOf(new byte[] {0x02});
        final byte[] sig = sign(hash, PRIV);
        sig[64] = 2;

        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, sig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v byte must be 0/1 (or 27/28)");
    }

    @Test
    void unparseableSignature_throwsIllegalArgumentException() {
        // r = s = 0xFF..FF exceeds the curve order, so the compact signature cannot be parsed
        final byte[] sig = new byte[65];
        Arrays.fill(sig, 0, 64, (byte) 0xFF);

        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(new byte[32], sig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed to parse recoverable signature");
    }

    @Test
    void validSignatureRecoversCorrectAddress() {
        final byte[] hash = MiscCryptoUtils.keccak256DigestOf(new byte[] {0x03, 0x04, 0x05});
        final byte[] sig = sign(hash, PRIV);

        assertThat(Secp256k1Recovery.recoverAddress(hash, sig)).isEqualTo(ADDR);
    }

    @Test
    void componentsOverload_recoversSameAddressAsPackedSignature() {
        final byte[] hash = MiscCryptoUtils.keccak256DigestOf(new byte[] {0x06});
        final byte[] sig = sign(hash, PRIV);
        final byte[] r = Arrays.copyOfRange(sig, 0, 32);
        final byte[] s = Arrays.copyOfRange(sig, 32, 64);

        assertThat(Secp256k1Recovery.recoverAddress(hash, r, s, sig[64])).isEqualTo(ADDR);
        assertThat(Secp256k1Recovery.recoverAddress(hash, r, s, sig[64] + 27)).isEqualTo(ADDR);
        assertThat(Secp256k1Recovery.recoverAddress(hash, r, s, sig[64]))
                .isEqualTo(Secp256k1Recovery.recoverAddress(hash, sig));
    }

    @Test
    void componentsOverload_rejectsBadInputs() {
        final byte[] hash = new byte[32];
        final byte[] c = new byte[32];

        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(new byte[31], c, c, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest must be 32 bytes");
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, new byte[31], c, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("r and s must each be 32 bytes");
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, c, new byte[33], 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("r and s must each be 32 bytes");
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, c, c, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v byte must be 0/1 (or 27/28), got 2");
        assertThatThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, c, c, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("got -1");
        assertThatNullPointerException().isThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, null, c, 0));
        assertThatNullPointerException().isThrownBy(() -> Secp256k1Recovery.recoverAddress(hash, c, null, 0));
    }

    /** Signs {@code hash32} with secp256k1; returns r‖s‖v (65 bytes, v = 0 or 1). */
    private static byte[] sign(final byte[] hash32, final byte[] privKey32) {
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, hash32, privKey32, null, null);
        final var compact = ByteBuffer.allocate(64);
        final var recId = new IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compact, recId, recoverableSig);
        final byte[] seal = new byte[65];
        System.arraycopy(compact.array(), 0, seal, 0, 64);
        seal[64] = (byte) recId.getValue();
        return seal;
    }
}
