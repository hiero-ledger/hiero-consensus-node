/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;

import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
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
