// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class ProofKeysAccessorImplTest {
    @Test
    void schnorrKeyPairToStringRedactsPrivateKey() {
        final var privateKey = Bytes.wrap("private");
        final var publicKey = Bytes.wrap("public");
        final var keyPair = new ProofKeysAccessorImpl.SchnorrKeyPair(privateKey, publicKey);

        final var asString = keyPair.toString();

        assertEquals("SchnorrKeyPair[privateKey=<redacted>, publicKey=" + publicKey + "]", asString);
        assertFalse(asString.contains(privateKey.toString()));
    }
}
