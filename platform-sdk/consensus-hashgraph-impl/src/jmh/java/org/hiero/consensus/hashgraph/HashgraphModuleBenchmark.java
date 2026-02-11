// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.hashgraph.config.ConsensusConfig_;
import org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.EventCounter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 3)
@Measurement(iterations = 3, time = 10)
public class HashgraphModuleBenchmark {
    private static final long SEED = 0;
    private static final int NUMBER_OF_EVENTS = 100000;

    @Param({"4", "10"})
    public int numNodes;

    @Param({"1", "4"})
    public int numOP;

    private HashgraphModule hashgraphModule;
    private List<PlatformEvent> events;
    private EventCounter counter;
    private ForkJoinPool threadPool;
    private WiringModel model;

    @Setup(Level.Trial)
    public void beforeBenchmark() {
        // Testing showed no change in consensus performance when using more than a single thread,
        // this makes sense since consensus only has a single component.
        final int numberOfThreads = 1;
        threadPool = ExecutorFactory.create("JMH", HashgraphModuleBenchmark::uncaughtException)
                .createForkJoinPool(numberOfThreads);
    }

    @TearDown(Level.Trial)
    public void afterBenchmark() {
        threadPool.shutdown();
    }

    @Setup(Level.Invocation)
    public void setup() {
        final Configuration config = new TestConfigBuilder()
                // does not seem to have any effect on performance
                .withValue(ConsensusConfig_.ROUNDS_NON_ANCIENT, 26)
                .getOrCreateConfig();
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withConfiguration(config).build();
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .seed(SEED)
                .maxOtherParents(numOP)
                .realSignatures(false)
                .numNodes(numNodes)
                .setPopulateNgen(true)
                .configuration(config)
                .build();
        events = generator.nextEvents(NUMBER_OF_EVENTS);
        model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .withDefaultPool(threadPool)
                .withWiringConfig(platformContext.getConfiguration().getConfigData(WiringConfig.class))
                .build();
        hashgraphModule = new DefaultHashgraphModule();
        hashgraphModule.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                generator.getRoster(),
                NodeId.of(generator.getRoster().rosterEntries().getFirst().nodeId()),
                i -> false,
                null);
        hashgraphModule.eventInputWire();
        counter = new EventCounter(NUMBER_OF_EVENTS);
        hashgraphModule.preconsensusEventOutputWire().solderForMonitoring(counter);
        model.start();
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        model.stop();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void calculateConsensus() {
        for (final PlatformEvent event : events) {
            hashgraphModule.eventInputWire().put(event);
        }
        counter.waitForAllEvents(5);
    }
    /*
    Results on a M1 Max MacBook Pro:

    HashgraphModuleBenchmark.calculateConsensus           4        1  thrpt    3  258606.721 ± 149086.677  ops/s
    HashgraphModuleBenchmark.calculateConsensus           4        4  thrpt    3  230154.189 ±  47948.349  ops/s
    HashgraphModuleBenchmark.calculateConsensus          10        1  thrpt    3  146482.156 ±  18662.312  ops/s
    HashgraphModuleBenchmark.calculateConsensus          10        4  thrpt    3  112123.113 ±  18273.060  ops/s
    */

    private static void uncaughtException(final Thread t, final Throwable e) {
        System.out.printf("Uncaught exception in thread %s: %s%n", t.getName(), e.getMessage());
        e.printStackTrace();
    }
}
