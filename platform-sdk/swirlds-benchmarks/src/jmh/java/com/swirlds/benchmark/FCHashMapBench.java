// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.swirlds.fchashmap.FCHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
public class FCHashMapBench extends BaseBench {

    String benchmarkName() {
        return "FCHashMapBench";
    }

    @Benchmark
    public void update() throws Exception {
        beforeTest("update");

        final long[] map = new long[verify ? maxKey : 0];
        FCHashMap<BenchmarkKey, BenchmarkValue> fcHashMap = new FCHashMap<>();

        // Update values
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                BenchmarkKey key = new BenchmarkKey(id);
                BenchmarkValue value = fcHashMap.get(key);
                long val = nextValue();
                if (value != null) {
                    if ((val & 0xff) == 0) {
                        fcHashMap.remove(key);
                        if (verify) map[(int) id] = 0L;
                    } else {
                        value = value.copyBuilder().update((l) -> l + val).build();
                        fcHashMap.put(key, value);
                        if (verify) map[(int) id] += val;
                    }
                } else {
                    fcHashMap.put(key, new BenchmarkValue(val));
                    if (verify) map[(int) id] = val;
                }
            }

            var newCopy = fcHashMap.copy();
            fcHashMap.release();
            fcHashMap = newCopy;
        }
        System.out.println("Updated " + numFiles + " copies in " + (System.currentTimeMillis() - start) + " ms");

        final var finalMap = fcHashMap;
        // Verify content
        if (verify) {
            start = System.currentTimeMillis();
            int count = 0;
            for (int id = 0; id < map.length; ++id) {
                BenchmarkValue dataItem = finalMap.get(new BenchmarkKey(id));
                if (dataItem == null) {
                    if (map[id] != 0L) {
                        throw new RuntimeException("Missing value");
                    }
                } else if (!dataItem.equals(new BenchmarkValue(map[id]))) {
                    throw new RuntimeException("Bad value");
                } else {
                    count += 1;
                }
            }
            System.out.println("Verified " + count + " keys in " + (System.currentTimeMillis() - start) + " ms");
        }

        afterTest(finalMap::release);
    }

    @Benchmark
    public void create() throws Exception {
        beforeTest("create");

        final BenchmarkValue[] map = new BenchmarkValue[verify ? maxKey : 0];
        FCHashMap<BenchmarkKey, BenchmarkValue> fcHashMap = new FCHashMap<>();
        System.out.println();

        // Write files
        long start = System.currentTimeMillis();
        for (int i = 0; i < numFiles; i++) {
            for (int j = 0; j < numRecords; ++j) {
                long id = Utils.randomLong(maxKey);
                BenchmarkKey key = new BenchmarkKey(id);
                BenchmarkValue value = new BenchmarkValue(nextValue());
                fcHashMap.put(key, value);
                if (verify) map[(int) id] = value;
            }

            var newCopy = fcHashMap.copy();
            fcHashMap.release();
            fcHashMap = newCopy;
        }
        System.out.println("Created " + numFiles + " copies in " + (System.currentTimeMillis() - start) + " ms");

        final var finalMap = fcHashMap;
        // Verify content
        if (verify) {
            start = System.currentTimeMillis();
            int count = 0;
            for (int id = 0; id < map.length; ++id) {
                BenchmarkValue dataItem = finalMap.get(new BenchmarkKey(id));
                if (dataItem == null) {
                    if (map[id] != null) {
                        throw new RuntimeException("Missing value");
                    }
                } else if (!dataItem.equals(map[id])) {
                    throw new RuntimeException("Bad value");
                } else {
                    count += 1;
                }
            }
            System.out.println("Verified " + count + " keys in " + (System.currentTimeMillis() - start) + " ms");
        }

        afterTest(finalMap::release);
    }

    /* The map is pre-created on demand, reused between benchmarks/iterations */
    private FCHashMap<BenchmarkKey, BenchmarkValue> fcHashMap;

    @TearDown
    public void destroyMap() {
        if (fcHashMap != null) {
            fcHashMap.release();
            fcHashMap = null;
        }
    }

    private void preCreateMap() {
        if (fcHashMap != null) return;
        fcHashMap = new FCHashMap<>();

        long start = System.currentTimeMillis();
        int count = 0;
        for (int i = 0; i < maxKey; i++) {
            BenchmarkKey key = new BenchmarkKey(i);
            BenchmarkValue value = new BenchmarkValue(nextValue());
            fcHashMap.put(key, value);

            if (++count == maxKey / numFiles) {
                count = 0;
                var newCopy = fcHashMap.copy();
                fcHashMap.release();
                fcHashMap = newCopy;
            }
        }
        System.out.println("Pre-created " + maxKey + " records in " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     *  Read from a pre-created map. Parallel.
     */
    @Benchmark
    public void read() throws Exception {
        beforeTest("read");
        preCreateMap();

        long start = System.currentTimeMillis();
        AtomicLong total = new AtomicLong(0);
        IntStream.range(0, numThreads).parallel().forEach(thread -> {
            long sum = 0;
            for (int i = 0; i < numRecords; ++i) {
                long id = Utils.randomLong(maxKey);
                BenchmarkValue value = fcHashMap.get(new BenchmarkKey(id));
                sum += value.hashCode();
            }
            total.addAndGet(sum);
        });
        System.out.println("Read " + ((long) numRecords * numThreads) + " records from " + numThreads + " threads in "
                + (System.currentTimeMillis() - start) + " ms");

        afterTest();
    }
}
