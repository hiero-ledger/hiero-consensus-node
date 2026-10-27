// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.validation;

import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;
import static org.hiero.consensus.benchmark.tools.event.EventShape.MAINNET;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRoster;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
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
 * Measures the latency of {@link DefaultEventFieldValidator#isValid(PlatformEvent)}, the internal validation that the
 * intake runs on every received event after hashing it. The validator checks that the required fields are set, that
 * the parents' hashes have the right length, that the transactions do not exceed the byte limit, that no two parents
 * have the same creator, and that the event's birth round is not lower than its parents'.
 *
 * <p>The cost grows with the number of parents and of transactions; the size of a transaction does not matter, because
 * the validator reads only its length. The events take their shape from mainnet's ({@link EventShape#MAINNET}): a self
 * parent and the median number of other parents, four, which 97% of mainnet's events have, and transactions of the
 * median size. The number of transactions per event is a parameter, at two quantiles of mainnet's events: 0, the median
 * (96% of the events carry none), and 2, the 99th percentile.
 *
 * <p>{@link EventStreamGenerator} generates the events once per trial from a fixed seed. Their parents are made up,
 * which does not matter here: the validator does not look them up. Every hash is a SHA-256 hash, as in production after
 * the event cutover. All events are valid, as nearly all are in production.
 *
 * <p>The first call of {@link PlatformEvent#getDescriptor()} creates the event's descriptor, and the event keeps it.
 * The validator makes that first call in production, so every invocation validates the events as received anew
 * ({@link EventStreamGenerator#receive(List)}): parsed from the gossip streams, built, and hashed outside the measured
 * region, as a node receives an event.
 *
 * <p>The validator gets the production metrics implementation, {@link DefaultPlatformMetrics}, not started, and the
 * system clock. It updates a metric and logs only when it rejects an event, so the measured calls use neither.
 *
 * <p>After every invocation, the number of valid events must equal the number of events. The validator stops at the
 * first check that fails, so an event is valid only if all checks ran on it.
 *
 * <p>One invocation, a pass, validates {@value #NUMBER_OF_EVENTS} events. In production, the validator checks an event
 * right after the hasher has read all of it; here, every pass receives and hashes its events right before it validates
 * them. On an Apple M4 laptop, passes of 1,000, 10,000, and 100,000 events made no consistent difference to the mean,
 * but passes of 1,000 gave less than half the samples of 10,000, too few for p99.99 in most 5 s iterations.
 *
 * <p>The primary score is the mean time per event. A {@link LatencyRecorder} also times every call but the first, with
 * one timestamp per call ({@link LatencyRecorder#lap()}), so each latency also contains the counting of the previous
 * call. {@link LatencyProfiler} reports the percentiles and the allocation per call. The allocation is measured around
 * the timed calls only, because {@code -prof gc} also counts the receiving in the setup.
 *
 * <p>The first call of a pass, right after the setup, is validated but not timed: it takes several microseconds. On an
 * Apple M4 laptop, timing it put p99.99 at 7.1–7.8 µs, against 1.1–3.4 µs without it, in alternating runs. No such
 * setup precedes an event in production.
 *
 * <p>A validation takes so little time that the timing is a noticeable part of every latency. On an Apple M4 laptop,
 * {@code lap()} added 8.0–8.7 ns to a call without transactions (5.5–6.0% of the timed call) and 1.3–2.5 ns to a call
 * with two transactions (0.7–1.3%), measured with and without laps in chunks interleaved in the same run; on the
 * benchmark machine it is unmeasured. A CPU profile overstates this share, at 10–11%: reading the clock waits for
 * the preceding instructions to finish, so the profiler charges their time to the clock read. The share is about
 * constant, so a regression of the validator still shows in full nanoseconds, but as a slightly smaller percentage.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation, so the latency results and
 * {@code latency.mean} exclude garbage collection. JMH's primary score, measured by JMH itself, still includes
 * discarded passes.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public class EventFieldValidatorBenchmark {

    /** The seed of the random number generator. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of events validated in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /**
     * The number of nodes that create the events. It does not change the validator's work, which only needs more nodes
     * than an event has parents.
     */
    private static final int NUMBER_OF_NODES = 32;

    /**
     * The transaction limits of the execution layer's defaults: {@code transaction.maxBytes} and
     * {@code transaction.maxTransactionBytesPerEvent} in {@code HederaConfig}.
     */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(6_144, 33_554_432);

    /** The number of transactions of every event: the median and the 99th percentile of {@link EventShape#MAINNET}. */
    @Param({"0", "2"})
    public int transactionsPerEvent;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The validator under test. */
    private DefaultEventFieldValidator validator;

    /** The generated events. */
    private List<PlatformEvent> generatedEvents;

    /** The events that the validator checks in one invocation, received anew for every invocation. */
    private List<PlatformEvent> events;

    /** The number of events the validator found valid in the invocation. */
    private int validEvents;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Generates the events and creates the validator. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void generateEvents() {
        final EventShape shape = new EventShape(
                constant(MAINNET.otherParents().median()),
                constant(transactionsPerEvent),
                constant(MAINNET.transactionSize().median()));

        // Every event birth round is past the cutover, so every event is hashed with SHA-256.
        EventHashFactory.initialize(0);
        final RosterWrapper roster = randomRoster(new Random(SEED), NUMBER_OF_NODES);
        generatedEvents = EventStreamGenerator.generateUnsignedEvents(shape, roster, NUMBER_OF_EVENTS, SEED);

        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                roster.nodeIdAtIndex(0),
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        validator = new DefaultEventFieldValidator(metrics, Time.getCurrent(), TRANSACTION_LIMITS);
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Prepares the benchmark invocation by receiving the generated events anew. This method is called once per
     * invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = EventStreamGenerator.receive(generatedEvents);
        validEvents = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies that the validator found every event valid. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        if (validEvents != NUMBER_OF_EVENTS) {
            throw new IllegalStateException(
                    "Expected %d valid events, but got %d".formatted(NUMBER_OF_EVENTS, validEvents));
        }
        events = null;
        recorder = null;
    }

    /**
     * Validates all events and records the latency of each call but the first.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void validateEvents() {
        recorder.startMeasurement();
        // The first call follows the setup, which makes it slow; it is validated but not timed.
        recorder.pause();
        if (validator.isValid(events.getFirst())) {
            validEvents++;
        }
        recorder.resume();
        for (int i = 1; i < NUMBER_OF_EVENTS; i++) {
            final boolean valid = validator.isValid(events.get(i));
            // One timestamp per call: each latency also contains the counting of the previous call.
            recorder.lap();
            if (valid) {
                validEvents++;
            }
        }
        recorder.stopMeasurement();
    }
}
