// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import java.util.Random;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.Signer;

/**
 * Creates random signatures with the source provided
 */
public class RandomSigner implements Signer {
    final Random random;

    public RandomSigner(final Random random) {
        this.random = random;
    }

    @Override
    public Signature sign(final byte[] data) {
        final byte[] sig = new byte[SignatureType.RSA.signatureLength()];
        random.nextBytes(sig);
        return new Signature(SignatureType.RSA, sig);
    }
}
