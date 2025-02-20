// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

public abstract class CryptoUtils {
    /** the type of hash to use */
    private static final String HASH_TYPE = "SHA-384";

    private static final String PRNG_TYPE = "SHA1PRNG";
    private static final String PRNG_PROVIDER = "SUN";

    // return the MessageDigest for the type of hash function used throughout the code
    public static MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance(HASH_TYPE);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create an instance of the default deterministic {@link SecureRandom}
     *
     * @return an instance of {@link SecureRandom}
     * @throws NoSuchProviderException
     * 		if the security provider is not available on the system
     * @throws NoSuchAlgorithmException
     * 		if the algorithm is not available on the system
     */
    public static SecureRandom getDetRandom() throws NoSuchProviderException, NoSuchAlgorithmException {
        return SecureRandom.getInstance(PRNG_TYPE, PRNG_PROVIDER);
    }
}
