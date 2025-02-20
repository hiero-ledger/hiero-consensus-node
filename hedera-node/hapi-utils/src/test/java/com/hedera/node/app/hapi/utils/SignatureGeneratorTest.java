// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.SignatureGenerator.BOUNCYCASTLE_PROVIDER;

import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SignatureGeneratorTest {
    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    @Test
    void rejectsNonEddsaKeys() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SignatureGenerator.signBytes(new byte[0], null));
    }

    @Test
    void acceptsEcdsaKey() throws Exception {
        final ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
        final java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("EC", BOUNCYCASTLE_PROVIDER);
        generator.initialize(ecSpec, new SecureRandom());
        final var kp = generator.generateKeyPair();
        Assertions.assertDoesNotThrow(() -> SignatureGenerator.signBytes("abc".getBytes(), kp.getPrivate()));
    }

    @Test
    void signsBytesCorrectly() throws Exception {
        final var pair = new KeyPairGenerator().generateKeyPair();
        final var sig = SignatureGenerator.signBytes("abc".getBytes(), pair.getPrivate());
        Assertions.assertEquals(64, sig.length);
    }
}
