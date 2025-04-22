// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.internal;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

public class DetRandomProvider {

    private static final String PRNG_TYPE = "SHA1PRNG";
    private static final String PRNG_PROVIDER = "SUN";

    private DetRandomProvider() {}

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
