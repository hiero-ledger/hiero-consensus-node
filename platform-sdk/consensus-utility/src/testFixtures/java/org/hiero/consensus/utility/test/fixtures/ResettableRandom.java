// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.utility.test.fixtures;

import java.util.Random;

/**
 * A {@link Random} that can be reset to its initial state.
 */
public class ResettableRandom extends Random {

    private final long initialSeed;

    /**
     * Create a source of randomness with a random initial seed.
     */
    public ResettableRandom() {
        this(new Random().nextLong());
    }

    /**
     * Create a source of randomness with a particular initial seed.
     *
     * @param seed
     * 		the initial seed
     */
    public ResettableRandom(final long seed) {
        super(seed);
        initialSeed = seed;
    }

    /**
     * Reset this object back to its initial state.
     */
    public void reset() {
        setSeed(initialSeed);
    }

    /**
     * Get the initial seed used to create this object.
     *
     * @return the initial seed
     */
    public long getInitialSeed() {
        return initialSeed;
    }
}
