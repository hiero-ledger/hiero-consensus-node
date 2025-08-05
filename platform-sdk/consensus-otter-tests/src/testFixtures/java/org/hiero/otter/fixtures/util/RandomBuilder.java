// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import java.util.Random;
import java.util.function.Supplier;

/**
 * A utility for building random number generators.
 */
public class RandomBuilder implements Supplier<Random> {

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
    @Override
    public Random get() {
        return new Random(seedSource.nextLong());
    }
}
