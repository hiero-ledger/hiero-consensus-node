// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RandomBuilderTest {

    @Test
    void randomSeedShouldBeRandomTest() {
        final RandomBuilder randomBuilderA = new RandomBuilder();
        final RandomBuilder randomBuilderB = new RandomBuilder();

        assertNotEquals(
                randomBuilderA.buildNonCryptographicRandom().nextLong(),
                randomBuilderB.buildNonCryptographicRandom().nextLong());
    }

    @Test
    void specifiedSeedShouldProduceTheSameResultsTest() {
        final Random random = getRandomPrintSeed();

        final long seed = random.nextLong();
        final RandomBuilder randomBuilderA = new RandomBuilder(seed);
        final RandomBuilder randomBuilderB = new RandomBuilder(seed);

        Random randomA = randomBuilderA.buildNonCryptographicRandom();
        Random randomB = randomBuilderB.buildNonCryptographicRandom();
        for (int i = 0; i < 1000; i++) {
            if (i % 7 == 0) {
                randomA = randomBuilderA.buildNonCryptographicRandom();
                randomB = randomBuilderB.buildNonCryptographicRandom();
            }
            assertEquals(randomA.nextLong(), randomB.nextLong());
        }
    }
}
