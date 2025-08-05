// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;

/**
 * A utility for building random number generators.
 */
public class RandomBuilder {

    private final Random seedSource;

    /**
     * Constructor. Random seed is used.
     */
    public RandomBuilder() {
        seedSource = new Random();
    }

    /**
     * Constructor.
     *
     * @param seed the seed for the random number generator
     */
    public RandomBuilder(final long seed) {
        seedSource = new Random(seed);
    }

    /**
     * Build a non-cryptographic random number generator.
     *
     * @return a non-cryptographic random number generator
     */
    @NonNull
    public Random buildNonCryptographicRandom() {
        return new Random(seedSource.nextLong());
    }
}
