// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.EventCounter;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.test.fixtures.RosterWithKeys;
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
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;

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
    /** The random seed used for deterministic event generation. */
    private static final long SEED = 0;

    /** The number of nodes in the simulated network. */
    @Param({"4"})
    public int numNodes;

    /** The number of threads in the {@link java.util.concurrent.ForkJoinPool} used by the wiring model. */
    @Param({"10"})
    public int numberOfThreads;

    @Param({"ED25519"})
    public SigningSchema signingSchema;

    @Param({"0.0", "0.5"})
    public double duplicateRate;

    @Param({"1","100","1000"})
    public int shuffleBatchSize;

    private PlatformContext platformContext;
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
        platformContext = TestPlatformContextBuilder.create().build();
        final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(new Random(SEED))
                .withSize(numNodes)
                .withRealKeysEnabled(true)
                .withSigningSchema(signingSchema)
                .buildWithKeys();
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .rosterWithKeys(rosterWithKeys)
                .maxOtherParents(1)
                .seed(SEED)
                .realSignatures(true)
                .build();
        final List<PlatformEvent> uniqueEvents = generator.nextEvents(NUMBER_OF_EVENTS);
        events = shuffleBatches(injectDuplicates(uniqueEvents));

        model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .enableJvmAnchor()
                .withDefaultPool(threadPool)
                .withWiringConfig(platformContext.getConfiguration().getConfigData(WiringConfig.class))
                .build();
        final RosterHistory rosterHistory =
                new RosterHistory(List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, rosterWithKeys.getRoster()));

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

    Benchmark               (duplicateRate)  (numNodes)  (numberOfThreads)  (shuffleBatchSize)  (signingSchema)   Mode  Cnt       Score       Error  Units
    IntakeBenchmark.intake              0.0           4                 10                   1          ED25519  thrpt    3  147082.323 ± 65218.633  ops/s
    IntakeBenchmark.intake              0.0           4                 10                 100          ED25519  thrpt    3  150490.768 ± 74712.088  ops/s
    IntakeBenchmark.intake              0.0           4                 10                1000          ED25519  thrpt    3  152585.029 ± 14062.522  ops/s
    IntakeBenchmark.intake              0.5           4                 10                   1          ED25519  thrpt    3  144408.659 ±  4639.713  ops/s
    IntakeBenchmark.intake              0.5           4                 10                 100          ED25519  thrpt    3  146459.959 ± 12895.160  ops/s
    IntakeBenchmark.intake              0.5           4                 10                1000          ED25519  thrpt    3  146133.726 ± 11340.480  ops/s
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

    /**
     * Creates a new list that contains all unique events plus duplicate copies of randomly selected events. The
     * number of duplicates is determined by the {@link #duplicateRate}: for example, a rate of 0.5 means that for
     * every unique event, there is a 50% chance it will be immediately followed by a duplicate. The resulting list
     * preserves the original order with duplicates interleaved.
     *
     * @param uniqueEvents the list of unique events to use as source
     * @return a new list containing unique events with duplicates interleaved
     */
    private List<PlatformEvent> injectDuplicates(
            @NonNull final List<PlatformEvent> uniqueEvents) {
        final Random random = new Random(SEED);
        if (duplicateRate <= 0.0) {
            return uniqueEvents;
        }
        final List<PlatformEvent> result = new ArrayList<>();
        for (final PlatformEvent event : uniqueEvents) {
            result.add(event);
            if (random.nextDouble() < duplicateRate) {
                result.add(event);
            }
        }
        return result;
    }

    private List<PlatformEvent> shuffleBatches(@NonNull final List<PlatformEvent> events) {
        if (shuffleBatchSize <= 1) {
            return events;
        }
        final List<PlatformEvent> result = new ArrayList<>(events.size());
        final Random random = new Random(SEED);
        for (int i = 0; i < events.size(); i += shuffleBatchSize) {
            final int end = Math.min(i + shuffleBatchSize, events.size());
            // shuffle the sublist in place
            final List<PlatformEvent> batch = events.subList(i, end);
            Collections.shuffle(batch, random);
            result.addAll(batch);
        }
        return result;
    }

    private static void uncaughtException(final Thread t, final Throwable e) {
        System.out.printf("Uncaught exception in thread %s: %s%n", t.getName(), e.getMessage());
        e.printStackTrace();
    }
}
