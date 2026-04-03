// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PRIVATE_ECDSA_KEY;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PUBLIC_ECDSA_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.hedera.node.app.hapi.utils.ethereum.BouncyCastleSecp256k1Support;
import java.math.BigInteger;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class Secp256k1ParityTest {
    private static final int RANDOM_CASES = 25;
    private final SplittableRandom random = new SplittableRandom(1_337);

    @Test
    void matchesPureBouncyCastleForKnownKeyOperations() {
        final var rawPublicKey = BouncyCastleSecp256k1Support.decompressCompressedPublicKey(TRUFFLE0_PUBLIC_ECDSA_KEY);

        assertArrayEquals(
                rawPublicKey, MiscCryptoUtils.decompressSecp256k1(TRUFFLE0_PUBLIC_ECDSA_KEY), "decompression should match");
        assertArrayEquals(
                TRUFFLE0_PUBLIC_ECDSA_KEY,
                MiscCryptoUtils.compressSecp256k1(rawPublicKey),
                "compression should match");
        assertArrayEquals(
                BouncyCastleSecp256k1Support.recoverAddressFromCompressedPublicKey(TRUFFLE0_PUBLIC_ECDSA_KEY),
                EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PUBLIC_ECDSA_KEY),
                "address derivation from public key should match");
        assertArrayEquals(
                BouncyCastleSecp256k1Support.recoverAddressFromCompressedPublicKey(TRUFFLE0_PUBLIC_ECDSA_KEY),
                EthSigsUtils.recoverAddressFromPrivateKey(TRUFFLE0_PRIVATE_ECDSA_KEY),
                "address derivation from private key should match");
    }

    @Test
    void matchesPureBouncyCastleForRandomKeyOperations() {
        for (int i = 0; i < RANDOM_CASES; i++) {
            final var privateKey = nextPrivateKey();
            final var compressedKey = BouncyCastleSecp256k1Support.deriveCompressedPublicKey(privateKey);
            final var rawPublicKey = BouncyCastleSecp256k1Support.decompressCompressedPublicKey(compressedKey);

            assertArrayEquals(
                    rawPublicKey,
                    MiscCryptoUtils.decompressSecp256k1(compressedKey),
                    "random decompression should match");
            assertArrayEquals(
                    compressedKey,
                    MiscCryptoUtils.compressSecp256k1(rawPublicKey),
                    "random compression should match");
            assertArrayEquals(
                    BouncyCastleSecp256k1Support.recoverAddressFromCompressedPublicKey(compressedKey),
                    EthSigsUtils.recoverAddressFromPubKey(compressedKey),
                    "random address derivation should match");
        }
    }

    private byte[] nextPrivateKey() {
        BigInteger candidate;
        do {
            final var bytes = new byte[32];
            random.nextBytes(bytes);
            candidate = new BigInteger(1, bytes);
        } while (candidate.signum() == 0
                || candidate.compareTo(BouncyCastleSecp256k1SupportTestConstants.CURVE_ORDER) >= 0);
        return to32Bytes(candidate);
    }

    private static byte[] to32Bytes(final BigInteger value) {
        final var input = value.toByteArray();
        final var output = new byte[32];
        System.arraycopy(input, Math.max(0, input.length - 32), output, Math.max(0, 32 - input.length), Math.min(32, input.length));
        return output;
    }

    /**
     * Keeps the test scoped to public API usage without widening the helper's surface unnecessarily.
     */
    static final class BouncyCastleSecp256k1SupportTestConstants {
        private static final BigInteger CURVE_ORDER = new BigInteger(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
                16);

        private BouncyCastleSecp256k1SupportTestConstants() {}
    }
}
