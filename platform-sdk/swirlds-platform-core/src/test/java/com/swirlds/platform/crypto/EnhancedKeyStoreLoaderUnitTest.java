// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-function unit tests for the package-private helpers in {@link EnhancedKeyStoreLoader} —
 * {@link EnhancedKeyStoreLoader#keyPairMatches(PrivateKey, java.security.PublicKey)} and
 * {@link EnhancedKeyStoreLoader#signatureAlgorithm(PrivateKey)}. End-to-end wiring tests live
 * in {@link EnhancedKeyStoreLoaderTest}.
 */
class EnhancedKeyStoreLoaderUnitTest {

    @Test
    @DisplayName("keyPairMatches returns true for a matching keypair")
    void matchingKeyPairReturnsTrue()
            throws KeyGeneratingException, KeyStoreException, NoSuchAlgorithmException, NoSuchProviderException,
                    InvalidKeyException, SignatureException {
        final KeysAndCerts keysAndCerts = KeysAndCertsGenerator.generate(NodeId.of(0));
        assertThat(EnhancedKeyStoreLoader.keyPairMatches(
                        keysAndCerts.sigKeyPair().getPrivate(),
                        keysAndCerts.sigCert().getPublicKey()))
                .isTrue();
    }

    @Test
    @DisplayName("keyPairMatches returns false for unrelated keys")
    void mismatchedKeyPairReturnsFalse()
            throws KeyGeneratingException, KeyStoreException, NoSuchAlgorithmException, NoSuchProviderException,
                    InvalidKeyException, SignatureException {
        final KeysAndCerts firstNodeKeys = KeysAndCertsGenerator.generate(NodeId.of(0));
        final KeysAndCerts secondNodeKeys = KeysAndCertsGenerator.generate(NodeId.of(1));
        assertThat(EnhancedKeyStoreLoader.keyPairMatches(
                        firstNodeKeys.sigKeyPair().getPrivate(),
                        secondNodeKeys.sigCert().getPublicKey()))
                .isFalse();
    }

    @Test
    @DisplayName("signatureAlgorithm rejects unsupported key algorithms")
    void signatureAlgorithmRejectsUnknown() {
        final PrivateKey unsupported = new PrivateKey() {
            @Override
            public String getAlgorithm() {
                return "BLS";
            }

            @Override
            public String getFormat() {
                return null;
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }
        };
        assertThatThrownBy(() -> EnhancedKeyStoreLoader.signatureAlgorithm(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported signing key algorithm: BLS");
    }
}
