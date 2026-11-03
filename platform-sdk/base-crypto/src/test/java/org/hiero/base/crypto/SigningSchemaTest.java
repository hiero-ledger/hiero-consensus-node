// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.stream.Stream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link SigningSchema#fromKeyType(Key)} and {@link SigningSchema#isSupported(Key)}. Keys come from both the
 * JDK and BouncyCastle, because the two report EdDSA keys under different algorithm names: the JDK uses "EdDSA" for
 * both Ed25519 and Ed448, BouncyCastle names the curve.
 */
class SigningSchemaTest {

    private static KeyPair jdkKeyPair(final String algorithm) throws GeneralSecurityException {
        return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
    }

    private static KeyPair bcKeyPair(final String algorithm) throws GeneralSecurityException {
        return KeyPairGenerator.getInstance(algorithm, new BouncyCastleProvider())
                .generateKeyPair();
    }

    static Stream<Arguments> supportedKeys() throws GeneralSecurityException {
        return Stream.of(
                Arguments.of("JDK RSA", jdkKeyPair("RSA"), SigningSchema.RSA),
                Arguments.of("JDK Ed25519", jdkKeyPair("Ed25519"), SigningSchema.ED25519),
                Arguments.of("BouncyCastle Ed25519", bcKeyPair("Ed25519"), SigningSchema.ED25519));
    }

    static Stream<Arguments> unsupportedKeys() throws GeneralSecurityException {
        return Stream.of(
                Arguments.of("JDK EC", jdkKeyPair("EC")),
                Arguments.of("JDK Ed448", jdkKeyPair("Ed448")),
                Arguments.of("BouncyCastle Ed448", bcKeyPair("Ed448")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedKeys")
    void supportedKeysAreAccepted(final String description, final KeyPair keyPair, final SigningSchema expected) {
        for (final Key key : new Key[] {keyPair.getPublic(), keyPair.getPrivate()}) {
            assertThat(SigningSchema.isSupported(key)).isTrue();
            assertThat(SigningSchema.fromKeyType(key)).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedKeys")
    void unsupportedKeysAreRejected(final String description, final KeyPair keyPair) {
        for (final Key key : new Key[] {keyPair.getPublic(), keyPair.getPrivate()}) {
            assertThat(SigningSchema.isSupported(key)).isFalse();
            assertThatThrownBy(() -> SigningSchema.fromKeyType(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
