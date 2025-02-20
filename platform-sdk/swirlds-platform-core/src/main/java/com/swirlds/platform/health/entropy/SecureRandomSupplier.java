// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health.entropy;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

/**
 * Supplies an instance of {@link SecureRandom}
 */
@FunctionalInterface
public interface SecureRandomSupplier {

    /**
     * Supplies an instance of {@link SecureRandom}
     *
     * @return the instance
     * @throws NoSuchProviderException
     * 		if the requested provider does not exist
     * @throws NoSuchAlgorithmException
     * 		if the requested algorithm does not exist
     */
    SecureRandom get() throws NoSuchProviderException, NoSuchAlgorithmException;
}
