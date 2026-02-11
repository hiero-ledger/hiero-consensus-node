// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.EventCounter;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;
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

/**
 * A JMH benchmark that measures the throughput of the event intake pipeline. Events are generated using a
 * {@link GeneratorEventGraphSource} with real cryptographic signatures, submitted to the intake module, and the benchmark
 * waits until all events have been validated and emitted.
 */
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 10)
public class IntakeBenchmark {
    /** The number of events to generate and process per benchmark invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;
    /** The number of nodes in the simulated network. */
    @Param({"4"})
    public int numNodes;

    /** The random seed used for deterministic event generation. */
    @Param({"0"})
    public long seed;

    /** The number of threads in the {@link java.util.concurrent.ForkJoinPool} used by the wiring model. */
    @Param({"10"})
    public int numberOfThreads;

    private PlatformContext platformContext;
    private Roster roster;
    private List<PlatformEvent> events;
    private DefaultEventIntakeModule intake;
    private EventCounter counter;
    private ForkJoinPool threadPool;
    private WiringModel model;

    /**
     * Executed once at the beginning of the benchmark.
     */
    @Setup(Level.Trial)
    public void beforeBenchmark() {
        platformContext = TestPlatformContextBuilder.create().build();
        threadPool = ExecutorFactory.create("JMH", IntakeBenchmark::uncaughtException)
                .createForkJoinPool(numberOfThreads);
    }

    /**
     * Executed once at the end of the benchmark.
     */
    @TearDown(Level.Trial)
    public void afterBenchmark() {
        threadPool.shutdown();
    }

    /**
     * Sets up the benchmark state before each invocation of the benchmarking method.
     * <br/>
     * At the moment, reconstructs the module every time. In the future, it would be better to reuse the same instance.
     */
    @Setup(Level.Invocation)
    public void beforeInvocation() {
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(numNodes)
                .maxOtherParents(1)
                .seed(seed)
                .realSignatures(true)
                .build();
        roster = generator.getRoster();
        events = generator.nextEvents(NUMBER_OF_EVENTS);

        model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .enableJvmAnchor()
                .withDefaultPool(threadPool)
                .withWiringConfig(platformContext.getConfiguration().getConfigData(WiringConfig.class))
                .build();
        final RosterHistory rosterHistory =
                new RosterHistory(List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, roster));

        intake = new DefaultEventIntakeModule();
        intake.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory,
                new NoOpIntakeEventCounter(),
                new TransactionLimits(1000, 1000),
                new EventPipelineTracker(platformContext.getMetrics()));
        counter = new EventCounter(NUMBER_OF_EVENTS);
        intake.validatedEventsOutputWire().solderForMonitoring(counter);
        // builds the input wire
        intake.unhashedEventsInputWire();
        model.start();
    }

    /**
     * Stops the wiring model after each benchmark invocation.
     */
    @TearDown(Level.Invocation)
    public void tearDown() {
        model.stop();
    }

    /*
    Results on a M1 Max MacBook Pro:

    */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void intake() {
        for (final PlatformEvent event : events) {
            intake.unhashedEventsInputWire().put(event);
        }
        counter.waitForAllEvents(5);
    }

    private static void uncaughtException(final Thread t, final Throwable e) {
        System.out.printf("Uncaught exception in thread %s: %s%n", t.getName(), e.getMessage());
        e.printStackTrace();
    }
}
