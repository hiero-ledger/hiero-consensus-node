// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.crypto;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.randomByteArray;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.SignatureType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A collection of utilities for generating random crypto data used for unit testing.
 */
public class CryptoRandomUtils {

    private CryptoRandomUtils() {}

    /**
     * Generates a random hash
     * @return a random hash
     */
    public static @NonNull Hash randomHash() {
        return randomHash(new Random());
    }

    /**
     * Generates a random hash
     * @param random
     * 		the random object to use
     * @return a random hash
     */
    public static @NonNull Hash randomHash(@NonNull final Random random) {
        return new Hash(randomByteArray(random, DigestType.SHA_384.digestLength()), DigestType.SHA_384);
    }

    /**
     * Generates Bytes with random data that is the same length as a SHA-384 hash
     * @param random the random object to use
     * @return random Bytes
     */
    public static Bytes randomHashBytes(@NonNull final Random random) {
        return Bytes.wrap(randomByteArray(random, DigestType.SHA_384.digestLength()));
    }

    /**
     * Get a random signature (doesn't actually sign anything, just random bytes)
     * @param random the random object to use
     * @return a random signature
     */
    public static @NonNull Signature randomSignature(@NonNull final Random random) {
        return new Signature(SignatureType.RSA, randomByteArray(random, SignatureType.RSA.signatureLength()));
    }

    /**
     * Get random signature bytes that is the same length as a RSA signature
     * @param random the random object to use
     * @return random signature bytes
     */
    public static @NonNull Bytes randomSignatureBytes(@NonNull final Random random) {
        return Bytes.wrap(randomByteArray(random, SignatureType.RSA.signatureLength()));
    }
}
