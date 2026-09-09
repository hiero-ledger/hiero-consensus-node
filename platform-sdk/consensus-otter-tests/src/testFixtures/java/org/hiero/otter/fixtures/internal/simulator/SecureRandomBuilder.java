// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.simulator;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

/**
 * A utility for building random number generators.
 *
 * <p>Simulated environments must never use {@link SecureRandom#SecureRandom()}: the default provider is
 * {@code NativePRNG}, which draws from the operating system and ignores {@link SecureRandom#setSeed(long)} for the
 * purpose of reproducibility. This builder pins {@code SHA1PRNG}, whose output is a pure function of the seed, so a
 * simulation that consumes it stays replayable.
 */
public class SecureRandomBuilder implements Supplier<SecureRandom> {

    private final Random seedSource;

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
