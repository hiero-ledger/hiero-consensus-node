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
 * <p>The intake validates an event right after it has received and hashed it, and the validator makes the first call of
 * {@link PlatformEvent#getDescriptor()}, which creates the event's descriptor. So the benchmark receives every event
 * anew right before it validates it ({@link EventStreamGenerator#receive(List)}): parsed from the gossip streams, built,
 * and hashed, with its data still in the CPU caches. Validating the events only after receiving all of a pass would
 * leave them out of the caches: on the benchmark machine, two transactions then cost about 480 ns more than none
 * (calibration run 40544670094), although the validator only reads their lengths.
 *
 * <p>The validator gets the production metrics implementation, {@link DefaultPlatformMetrics}, not started, and the
 * system clock. It updates a metric and logs only when it rejects an event, so the measured calls use neither.
 *
 * <p>After every invocation, the number of valid events must equal the number of events. The validator stops at the
 * first check that fails, so an event is valid only if all checks ran on it.
 *
 * <p>One invocation, a pass, receives and validates {@value #NUMBER_OF_EVENTS} events. A {@link LatencyRecorder} is
 * paused while an event is received, so the receiving counts neither in a latency nor in the allocation. It times each
 * validation from {@link LatencyRecorder#resume()} right before the call to {@link LatencyRecorder#lap()} right after
 * it, and {@link LatencyProfiler} reports the percentiles and the allocation per validation. JMH's primary score, the
 * mean time per event of the whole pass, includes the receiving; track {@code latency.mean} instead.
 *
 * <p>The first call of a pass, right after the setup, is validated but not timed: it is much slower than the others. On
 * an Apple M4 laptop, timing it put p99.99 at 0.6–1.0 µs, against 0.17–0.54 µs without it, in alternating runs. No such
 * setup precedes an event in production.
 *
 * <p>Each latency also contains one reading of the clock, which is a noticeable part of a call this short. On an Apple
 * M4 laptop, an empty call, {@code resume()} directly followed by {@code lap()}, took 11.1–11.3 ns on average, about a
 * fifth of a validation (53–59 ns); on the benchmark machine it is unmeasured. The share is about constant, so a
 * regression of the validator still shows in full nanoseconds, but as a smaller percentage.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the pass, although
 * receiving allocates several kilobytes per event. A pass during which one does is discarded and replaced by the next
 * invocation, so the latency results and {@code latency.mean} exclude garbage collection.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-30, 7956479b66</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms1g -Xmx1g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: transactionsPerEvent=0 / transactionsPerEvent=2</li>
 *   <li>Steady state after: 5 s / 5 s</li>
 *   <li>Noise (latency.mean, CV): 0.27% / 0.36% per 10 s iteration, 0.96% / 1.19% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 0.64% / 0.79% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 2.4 M / 1.5 M; expected stable: mean, p50, p90, p99, p99.9 / mean, p50, p90, p99</li>
 *   <li>Runtime: 4.6 / 4.6 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41018545353, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41020351322</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 9,
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

    /** The generated events. Every invocation receives each of them anew before it validates it. */
    private List<PlatformEvent> generatedEvents;

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
     * Prepares the benchmark invocation. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        validEvents = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the previous invocation now, so that it is not collected during the measured calls.
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
        recorder = null;
    }

    /**
     * Receives every event anew and validates it right away, as the intake does, and records the latency of each
     * validation but the first. The recorder is paused while an event is received.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void validateEvents() {
        recorder.startMeasurement();
        recorder.pause();
        // The first call follows the setup, which makes it slow; it is validated but not timed.
        if (validator.isValid(receive(generatedEvents.getFirst()))) {
            validEvents++;
        }
        for (int i = 1; i < NUMBER_OF_EVENTS; i++) {
            final PlatformEvent event = receive(generatedEvents.get(i));
            recorder.resume();
            final boolean valid = validator.isValid(event);
            recorder.lap();
            recorder.pause();
            if (valid) {
                validEvents++;
            }
        }
        recorder.resume();
        recorder.stopMeasurement();
    }

    /** Receives a generated event anew, as the intake receives it: parsed from the gossip streams, built, and hashed. */
    private static PlatformEvent receive(final PlatformEvent generatedEvent) {
        return EventStreamGenerator.receive(List.of(generatedEvent)).getFirst();
    }
}
