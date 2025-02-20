// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.cache;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;

import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MTModifyTest {

    private static final VirtualMapConfig VIRTUAL_MAP_CONFIG = CONFIGURATION.getConfigData(VirtualMapConfig.class);

    private static final Random rand = new Random();

    @Test
    public void mtModifyTest() throws Exception {
        VirtualNodeCache<TestKey, TestValue> cache = new VirtualNodeCache<>(VIRTUAL_MAP_CONFIG);
        final int maxKey = 100;
        // Populate the cache
        for (int i = 0; i < maxKey; i++) {
            final TestKey virtualPutKey = new TestKey(i);
            final String rawPutValue = String.valueOf(rand.nextInt());
            final TestValue virtualPutValue = new TestValue(rawPutValue);
            cache.putLeaf(new VirtualLeafRecord<>(i, virtualPutKey, virtualPutValue));
        }
        final int threads = 5;
        // Run multiple iterations, as multi-threading issues are sometimes hard to catch
        final int iterations = 10000;
        for (int l = 0; l < iterations; l++) {
            final VirtualNodeCache<TestKey, TestValue> cache2 = cache.copy();
            cache = cache2;
            final Thread[] workers = new Thread[threads];
            final Map<Integer, Map<Integer, VirtualLeafRecord<TestKey, TestValue>>> toModifyCache =
                    new ConcurrentHashMap<>();
            // Multiple threads competing with each other to call get for modify
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                final Runnable run = () -> {
                    // Store leaves to modify for each key, per thread
                    toModifyCache.put(threadId, new ConcurrentHashMap<>());
                    for (int i = 0; i < maxKey; i++) {
                        final int rawModifyKey = rand.nextInt(maxKey);
                        final TestKey virtualModifyKey = new TestKey(rawModifyKey);
                        final VirtualLeafRecord<TestKey, TestValue> leafRecord =
                                cache2.lookupLeafByKey(virtualModifyKey, true);
                        toModifyCache.get(threadId).put(rawModifyKey, leafRecord);
                        Thread.yield();

                        // Modify value. It will trigger mutation update in the cache on the next key lookup
                        final TestValue virtualModifyValue = leafRecord.getValue();
                        final String rawModifyValue =
                                String.valueOf(Integer.parseInt(virtualModifyValue.value()) + rand.nextInt(100));
                        virtualModifyValue.setValue(rawModifyValue);
                    }
                };
                workers[t] = new Thread(run);
                workers[t].start();
            }
            for (Thread thread : workers) {
                thread.join();
            }

            // Now run the checks. Every thread has its own set of leaf records that can be used to modify
            // values. For each thread and each key, modify the value and check the corresponding value in
            // the cache is updated
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < maxKey; i++) {
                    final VirtualLeafRecord<TestKey, TestValue> leafRecord =
                            toModifyCache.get(t).get(i);
                    if (leafRecord != null) {
                        final String newValue = String.valueOf(rand.nextInt());
                        leafRecord.getValue().setValue(newValue);
                        final VirtualLeafRecord<TestKey, TestValue> fromCache =
                                cache2.lookupLeafByKey(new TestKey(i), false);
                        Assertions.assertEquals(newValue, fromCache.getValue().value());
                    }
                }
            }
        }
    }
}
