// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;

import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.Test;

class VirtualMapRandomTest {
    private static final int NUM_ROUNDS = 55;
    private static final int NUM_OPS_PER_ROUND = 100;
    private static final int KEY_SPACE_SIZE = 25;

    interface RandomOp extends Consumer<VirtualMapValidator<TestKey, TestValue>> {}

    private static final List<RandomOp> POSSIBLE_OPS = List.of(
            v -> v.put(randomKey(), randomValue()), // Insert key, replace key
            v -> v.get(randomKey()), // Fetch key (immutable)
            v -> { // Fetch-Mutate key
                final TestKey key = randomKey();
                TestValue toMutate = v.get(key);
                if (toMutate != null) {
                    toMutate = new TestValue(randomString());
                    v.put(key, toMutate);
                }
            },
            v -> v.remove(randomKey()) // Attempt to delete (including non-existent keys)
            );
    private static final Random RANDOM = RandomUtils.getRandomPrintSeed();

    private static TestKey randomKey() {
        return new TestKey(RANDOM.nextInt(KEY_SPACE_SIZE));
    }

    private static TestValue randomValue() {
        return new TestValue(RANDOM.nextLong());
    }

    private static String randomString() {
        return Long.toString(RANDOM.nextLong());
    }

    private static RandomOp getRandomOp() {
        return POSSIBLE_OPS.get(RANDOM.nextInt(POSSIBLE_OPS.size()));
    }

    @Test
    void randomOpTest() {
        final VirtualMapValidator<TestKey, TestValue> mapValidator = new VirtualMapValidator<>(createMap());
        for (int i = 0; i < NUM_ROUNDS; i++) {
            for (int j = 0; j < NUM_OPS_PER_ROUND; j++) {
                getRandomOp().accept(mapValidator);
            }
            mapValidator.newCopy();
        }
        mapValidator.validate();
    }
}
