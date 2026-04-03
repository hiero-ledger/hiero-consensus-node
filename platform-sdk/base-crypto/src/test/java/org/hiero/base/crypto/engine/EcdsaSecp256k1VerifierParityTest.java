// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.asRawEcdsaSecp256k1Key;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.genEcdsaSecp256k1KeyPair;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.signDigestWithEcdsaSecp256k1;

import java.math.BigInteger;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

class EcdsaSecp256k1VerifierParityTest {
    private static final int RANDOM_CASES = 25;
    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    private static final BigInteger CURVE_ORDER = CURVE.getN();

    @Test
    void matchesPureBouncyCastleForGeneratedSignatures() throws Exception {
        final var verifier = new EcdsaSecp256k1Verifier();
        final var random = new SplittableRandom(8_675_309L);

        for (int i = 0; i < RANDOM_CASES; i++) {
            final var keyPair = genEcdsaSecp256k1KeyPair();
            final var digest = new byte[EcdsaSecp256k1Verifier.ECDSA_KECCAK_256_SIZE];
            random.nextBytes(digest);

            final var rawSignature = signDigestWithEcdsaSecp256k1(keyPair.getPrivate(), digest);
            final var rawPublicKey = asRawEcdsaSecp256k1Key((ECPublicKey) keyPair.getPublic());
            final var highSRawSignature = toHighSSignature(rawSignature);

            assertThat(verifier.verify(rawSignature, digest, rawPublicKey))
                    .as("native verifier should match BC for standard signatures")
                    .isEqualTo(verifyWithBouncyCastle(rawSignature, digest, rawPublicKey));
            assertThat(verifier.verify(highSRawSignature, digest, rawPublicKey))
                    .as("native verifier should match BC for high-S signatures")
                    .isEqualTo(verifyWithBouncyCastle(highSRawSignature, digest, rawPublicKey));
        }
    }

    private static boolean verifyWithBouncyCastle(
            final byte[] rawSignature, final byte[] digest, final byte[] rawPublicKey) {
        final var signer = new ECDSASigner();
        signer.init(false, new ECPublicKeyParameters(pointFromRawPublicKey(rawPublicKey), DOMAIN));
        return signer.verifySignature(digest, new BigInteger(1, Arrays.copyOfRange(rawSignature, 0, 32)), new BigInteger(1, Arrays.copyOfRange(rawSignature, 32, 64)));
    }

    private static byte[] toHighSSignature(final byte[] rawSignature) {
        final var highS = CURVE_ORDER.subtract(new BigInteger(1, Arrays.copyOfRange(rawSignature, 32, 64)));
        final var highSSignature = Arrays.copyOf(rawSignature, rawSignature.length);
        System.arraycopy(to32Bytes(highS), 0, highSSignature, 32, 32);
        return highSSignature;
    }

    private static ECPoint pointFromRawPublicKey(final byte[] rawPublicKey) {
        final var encoded = new byte[65];
        encoded[0] = 0x04;
        System.arraycopy(rawPublicKey, 0, encoded, 1, rawPublicKey.length);
        return CURVE.getCurve().decodePoint(encoded).normalize();
    }

    private static byte[] to32Bytes(final BigInteger value) {
        final var input = value.toByteArray();
        final var output = new byte[32];
        System.arraycopy(input, Math.max(0, input.length - 32), output, Math.max(0, 32 - input.length), Math.min(32, input.length));
        return output;
    }
}
