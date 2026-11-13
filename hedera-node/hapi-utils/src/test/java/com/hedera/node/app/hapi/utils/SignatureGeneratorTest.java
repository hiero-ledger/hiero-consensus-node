// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import java.security.KeyPair;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.hiero.base.crypto.ECDSASecp256k1;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SignatureGeneratorTest {

    @Test
    void rejectsNonEddsaKeys() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SignatureGenerator.signBytes(new byte[Libsecp256k1.SIGNATURE_BYTES], null));
    }

    @Test
    void acceptsEcdsaKey() {
        final KeyPair kp = ECDSASecp256k1.genEcdsaSecp256k1KeyPair();
        Assertions.assertDoesNotThrow(
                () -> SignatureGenerator.signBytes(MiscCryptoUtils.keccak256DigestOf("abc"), kp.getPrivate()));
    }

    @Test
    void signsBytesCorrectly() throws Exception {
        final var pair = new KeyPairGenerator().generateKeyPair();
        final var sig = SignatureGenerator.signBytes("abc".getBytes(), pair.getPrivate());
        Assertions.assertEquals(64, sig.length);
    }
}
