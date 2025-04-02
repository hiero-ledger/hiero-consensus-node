// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;

import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Random FCHashMap Tests")
class RandomFCHashMapTests {

    @Test
    @DisplayName("Random Operations")
    void randomOperations() {

        final Random random = getRandomPrintSeed();

        final int iterations = 100_000;
        final int maxCopiesInMemory = 10;
        final int operationsPerCopy = 100;
        final int maxKey = 500;

        final VerifiedFCHashMapSeries<Integer, FCInt> sequence = new VerifiedFCHashMapSeries<>();

        for (int iteration = 0; iteration < iterations; iteration++) {

            final int putValue = random.nextInt();
            sequence.put(random.nextInt(maxKey), new FCInt(putValue), new FCInt(putValue));
            sequence.remove(random.nextInt(maxKey));

            final VerifiedFCHashMapSeries.ModifiableValues<FCInt> modifiableValues =
                    sequence.getForModify(random.nextInt(maxKey));
            final int getForModifyValue = random.nextInt();
            if (modifiableValues.value() != null) {
                modifiableValues.referenceValue().setValue(getForModifyValue);
                modifiableValues.value().setValue(getForModifyValue);
            }

            if (iteration % operationsPerCopy == 0) {
                sequence.copy();

                if (sequence.getNumberOfCopies() > maxCopiesInMemory) {
                    sequence.releaseRandom(random);
                }

                sequence.assertValidity();
            }
        }

        // Destroy all remaining copies
        while (sequence.getNumberOfCopies() > 0) {
            sequence.releaseRandom(random);
            sequence.assertValidity();
        }
    }
}
