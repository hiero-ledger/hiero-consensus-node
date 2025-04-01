// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.streaming;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.BlockState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for measuring the performance of the {@link BlockNodeConnectionManager#createPublishStreamRequests} method.
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class BlockNodeConnectionManagerBenchmark {

    private static final int BATCH_SIZE = 256;
    private BlockState block;

    @Param({"10000"})
    private int itemCount;

    @Setup
    public void setup() {
        // Create test blocks of different sizes
        block = createTestBlock(itemCount);
    }

    @Benchmark
    public void benchmarkCreatePublishStreamRequests(Blackhole blackhole) {
        List<PublishStreamRequest> requests = BlockNodeConnectionManager.createPublishStreamRequests(block, BATCH_SIZE);
        blackhole.consume(requests);
    }

    /**
     * Creates a test BlockState with the specified number of items.
     * Each item has a realistic size to better simulate real-world conditions.
     *
     * @param itemCount number of block items to create
     * @return a BlockState with the specified number of items
     */
    private BlockState createTestBlock(int itemCount) {
        List<BlockItem> items = new ArrayList<>(itemCount);

        // Create dummy block items with realistic data
        for (int i = 0; i < itemCount; i++) {
            // Generate random bytes used for application transaction
            byte[] randomBytes = new byte[1024];
            new Random().nextBytes(randomBytes);

            // Create a sample BlockItem protobuf message with some data to make it more realistic
            BlockItem blockItem = BlockItem.newBuilder()
                    .eventTransaction(
                            // Add some dummy data
                            EventTransaction.newBuilder()
                                    .applicationTransaction(Bytes.wrap(randomBytes))
                                    .build())
                    .build();

            items.add(blockItem);
        }

        // Create a BlockState with the items
        return new BlockState(1L, items);
    }

    /**
     * Main method to run the benchmark from the command line.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BlockNodeConnectionManagerBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
