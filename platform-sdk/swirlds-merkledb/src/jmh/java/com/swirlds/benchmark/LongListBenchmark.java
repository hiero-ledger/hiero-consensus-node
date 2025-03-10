// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.collections.LongListOffHeap;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LongListBenchmark {
    public static final int INITIAL_DATA_SIZE = 10_000_000;
    public Random random;
    private int randomIndex;
    private LongList list;
    private int nextIndex = INITIAL_DATA_SIZE;

    @Param({"LongListHeap", "LongListOffHeap"})
    public String listImpl;

    @Setup(Level.Trial)
    public void setup() {
        random = new Random(1234);
        list = switch (listImpl) {
            default -> new LongListHeap(1024 * 1024, INITIAL_DATA_SIZE, 256 * 1024);
            case "LongListOffHeap" -> new LongListOffHeap(1024 * 1024, INITIAL_DATA_SIZE, 256 * 1024);};
        // fill with some data
        for (int i = 0; i < INITIAL_DATA_SIZE; i++) {
            list.put(i, i + 1);
        }
        // print memory usage
        System.out.printf("Memory for initial %,d accounts:\n", INITIAL_DATA_SIZE);
        printMemoryUsage();
    }

    @Setup(Level.Invocation)
    public void randomIndex() {
        randomIndex = random.nextInt(INITIAL_DATA_SIZE - 1) + 1;
    }

    @Benchmark
    public void a_randomGet() {
        list.get(randomIndex, -1);
    }

    @Benchmark
    public void b_randomSet() {
        list.put(randomIndex, randomIndex * 2L);
    }

    @Benchmark
    public void a_randomCompareAndSet() {
        list.putIfEqual(randomIndex, randomIndex, randomIndex * 2L);
    }

    @Benchmark
    public void b_add() throws Exception {
        list.put(nextIndex, nextIndex);
        nextIndex++;
    }

    @Benchmark
    public void a_multiThreadedRead10k() {
        IntStream.range(0, 5).parallel().forEach(jobID -> {
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 2000; i++) {
                list.get(random.nextInt(INITIAL_DATA_SIZE), -1);
            }
        });
    }

    @Benchmark
    public void b_multiThreadedReadPut10kEach() {
        IntStream.range(0, 5).parallel().forEach(jobID -> {
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 2000; i++) {
                list.get(random.nextInt(INITIAL_DATA_SIZE), -1);
                list.put(random.nextInt(INITIAL_DATA_SIZE), random.nextLong());
            }
        });
    }

    public void printMemoryUsage() {
        for (final MemoryPoolMXBean mpBean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (mpBean.getType() == MemoryType.HEAP) {
                System.out.printf("     Name: %s: %s\n", mpBean.getName(), mpBean.getUsage());
            }
        }
        System.out.println("    Runtime.getRuntime().totalMemory() = "
                + Runtime.getRuntime().totalMemory());
    }
}
