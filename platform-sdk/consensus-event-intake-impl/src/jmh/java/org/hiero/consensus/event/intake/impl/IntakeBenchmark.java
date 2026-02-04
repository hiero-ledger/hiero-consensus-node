// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.platform.test.fixtures.event.generator.GeneratorEventGraphSource;
import com.swirlds.platform.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A JMH benchmark that measures the throughput of the event intake pipeline. Events are generated using a
 * {@link GeneratorEventGraphSource} with real cryptographic signatures, submitted to the intake module, and the benchmark
 * waits until all events have been validated and emitted.
 */
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 3)
public class IntakeBenchmark {
    /** The number of nodes in the simulated network. */
    @Param({"4"})
    public int numNodes;

    /** The number of events to generate and process per benchmark invocation. */
    @Param({"10000"})
    public int numEvents;

    /** The random seed used for deterministic event generation. */
    @Param({"0"})
    public long seed;

    /** The number of threads in the {@link java.util.concurrent.ForkJoinPool} used by the wiring model. */
    @Param({"10"})
    public int numberOfThreads;

    private List<PlatformEvent> events;
    private DefaultEventIntakeModule intake;
    private EventCounter counter;
    private WiringModel model;

    /**
     * Sets up the benchmark state before each invocation. Creates a graph generator with real signatures, generates
     * events, initializes the wiring model and intake module, and wires the {@link EventCounter} to the validated
     * events output.
     */
    @Setup(Level.Invocation)
    public void setup() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(numNodes)
                .maxOtherParents(1)
                .seed(seed)
                .realSignatures(true)
                .build();
        events = generator.generateEvents(numEvents);
        // remove the hashes to force recalculation
        events.forEach(e -> e.setHash(null));

        final WiringConfig wiringConfig = platformContext.getConfiguration().getConfigData(WiringConfig.class);
        final ForkJoinPool defaultPool = platformContext.getExecutorFactory().createForkJoinPool(numberOfThreads);
        model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .enableJvmAnchor()
                .withDefaultPool(defaultPool)
                .withWiringConfig(wiringConfig)
                .build();
        final RosterHistory rosterHistory = new RosterHistory(
                List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, generator.getRoster()));

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
        counter = new EventCounter(numEvents);
        intake.validatedEventsOutputWire().solderForMonitoring(counter);
        // builds the input wire
        intake.unhashedEventsInputWire();
        model.start();
    }

    @TearDown(Level.Iteration)
    public void aaaaa() {
        System.out.println("Teardown after iteration");
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
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void intake() {
        for (final PlatformEvent event : events) {
            intake.unhashedEventsInputWire().put(event);
        }
        counter.waitForAllEvents();
    }
}
