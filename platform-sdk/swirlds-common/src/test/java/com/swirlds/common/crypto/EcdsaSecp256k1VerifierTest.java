// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.crypto.engine.EcdsaSecp256k1Verifier;
import com.swirlds.common.test.fixtures.crypto.EcdsaUtils;
import java.security.KeyPair;
import java.security.Security;
import java.security.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test class to verify the signatures of ECDSA(secp256k1) keys.
 */
class EcdsaSecp256k1VerifierTest {
    static {
        // add provider only if it's not in the JVM
        Security.addProvider(new BouncyCastleProvider());
    }

    private final EcdsaSecp256k1Verifier subject = new EcdsaSecp256k1Verifier();

    @BeforeAll
    static void setupClass() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void verifySignatureVerification() throws Exception {
        for (int i = 0; i < 1000; i++) {
            final KeyPair pair = EcdsaUtils.genEcdsaSecp256k1KeyPair();

            final byte[] signature = EcdsaUtils.signWellKnownDigestWithEcdsaSecp256k1(pair.getPrivate());
            final byte[] rawPubKey = EcdsaUtils.asRawEcdsaSecp256k1Key((ECPublicKey) pair.getPublic());

            final boolean isValid = subject.verify(signature, EcdsaUtils.WELL_KNOWN_DIGEST, rawPubKey);

            assertTrue(isValid, "signature should be valid");
        }
    }
}
