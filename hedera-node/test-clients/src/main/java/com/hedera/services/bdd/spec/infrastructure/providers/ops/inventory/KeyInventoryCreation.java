// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.inventory;

import static com.hedera.services.bdd.spec.keys.KeyShape.randomly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

public class KeyInventoryCreation {
    public static final int DEFAULT_NUM_KEYS = 10;
    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int DEFAULT_MAX_LIST_SIZE = 3;
    public static final int DEFAULT_MAX_THRESHOLD_SIZE = 7;
    public static final double DEFAULT_LIST_PROB = 0.2;
    public static final double DEFAULT_SIMPLE_PROB = 0.5;
    public static final double DEFAULT_THRESHOLD_PROB = 0.3;

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private Random r = new Random(468417L);

    private int numKeys = DEFAULT_NUM_KEYS;
    private int maxDepth = DEFAULT_MAX_DEPTH;
    private int maxListSize = DEFAULT_MAX_LIST_SIZE;
    private int maxThresholdSize = DEFAULT_MAX_THRESHOLD_SIZE;
    private double listProb = DEFAULT_LIST_PROB;
    private double simpleProb = DEFAULT_SIMPLE_PROB;
    private double thresholdProb = DEFAULT_THRESHOLD_PROB;

    public HapiSpecOperation[] creationOps() {
        return IntStream.range(0, numKeys)
                .mapToObj(i -> newKeyNamed("randKey" + i)
                        .shape(randomly(maxDepth, this::someListSize, this::someType, this::someThresholdSizes)))
                .toArray(HapiSpecOperation[]::new);
    }

    public KeyFactory.KeyType someType() {
        double t = r.nextDouble();
        if (t < listProb) {
            return KeyFactory.KeyType.LIST;
        } else if (t < (listProb + simpleProb)) {
            return KeyFactory.KeyType.SIMPLE;
        } else if (t < (listProb + simpleProb + thresholdProb)) {
            return KeyFactory.KeyType.THRESHOLD;
        } else {
            throw new IllegalStateException("Underdetermined key type!");
        }
    }

    public int someListSize() {
        return r.nextInt(maxListSize) + 1;
    }

    public Map.Entry<Integer, Integer> someThresholdSizes() {
        int N = r.nextInt(maxThresholdSize) + 1;
        int M = r.nextInt(N) + 1;
        return new AbstractMap.SimpleEntry<>(M, N);
    }
}
