// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

/**
 * A utility for building random number generators.
 */
public class SecureRandomBuilder implements Supplier<SecureRandom> {

    private final Random seedSource;

    /**
     * Constructor. Random seed is used.
     */
    public SecureRandomBuilder() {
        seedSource = new Random();
    }

    /**
     * Constructor.
     *
     * @param seed the seed for the random number generator
     */
    public SecureRandomBuilder(final long seed) {
        seedSource = new Random(seed);
    }

    /**
     * Build a non-cryptographic random number generator.
     *
     * @return a non-cryptographic random number generator
     */
    @Override
    public SecureRandom get() {

        // Use SHA1PRNG for deterministic behavior
        final SecureRandom secureRandom;
        try {
            secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
        } catch (final NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException(e);
        }
        // Set a fixed seed for deterministic output
        secureRandom.setSeed(seedSource.nextLong());
        return secureRandom;
    }
}
