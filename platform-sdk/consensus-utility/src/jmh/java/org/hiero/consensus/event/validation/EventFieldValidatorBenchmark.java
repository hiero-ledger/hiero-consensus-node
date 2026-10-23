// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.validation;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
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
 * the validator reads only its length. Every event has a self parent and {@value #OTHER_PARENTS} other parents, the most
 * that the default {@code maxOtherParents} of event creation allows. The number of transactions per event is a
 * parameter: on mainnet, events carry fewer than 0.1 transactions on average (metric {@code trans_per_event}), so most
 * carry none.
 *
 * <p>The events are generated once per trial from a fixed seed. Each one is serialized and parsed, as gossip receives
 * it, and hashed by the production hasher. All events are valid, as nearly all are in production.
 *
 * <p>The first call of {@link PlatformEvent#getDescriptor()} creates the event's descriptor, and the event keeps it.
 * The validator makes that first call in production, so every invocation works on fresh copies of the events, created
 * outside the measured region as gossip creates a received event.
 *
 * <p>The validator gets the production metrics implementation, {@link DefaultPlatformMetrics}, not started, and the
 * system clock. It updates a metric and logs only when it rejects an event, so the measured calls use neither.
 *
 * <p>After every invocation, the number of valid events must equal the number of events. The validator stops at the
 * first check that fails, so an event is valid only if all checks ran on it.
 *
 * <p>One invocation, a pass, validates {@value #NUMBER_OF_EVENTS} events. In production, the validator checks an event
 * right after the hasher has read all of it. On an Apple M4 laptop, passes of 100,000 events doubled the mean, likely
 * because they no longer fit the caches; passes of 1,000 and 10,000 events gave the same results, but 1,000 gave a
 * quarter of the samples.
 *
 * <p>The primary score is the mean time per event. A {@link LatencyRecorder} also times every call but the first, with
 * one timestamp per call ({@link LatencyRecorder#lap()}), so each latency also contains the counting of the previous
 * call. {@link LatencyProfiler} reports the percentiles and the allocation per call. The allocation is measured around
 * the timed calls only, because {@code -prof gc} also counts the copying in the setup.
 *
 * <p>The first call of a pass, right after the setup, is validated but not timed: it takes several microseconds. On an
 * Apple M4 laptop, timing it put p99.99 at 8.7–9.7 µs, against 2.8–3.5 µs without it, in alternating runs. No such
 * setup precedes an event in production.
 *
 * <p>A validation takes so little time that the timing is a noticeable part of every latency. On an Apple M4 laptop,
 * {@code lap()} added 3.7–7.0 ns to a call without transactions (2.1–3.4% of the timed call) and 2.7–4.3 ns to a call
 * with one transaction (1.3–2.2%), measured with and without laps in chunks interleaved in the same run; on the
 * benchmark machine it is unmeasured. A CPU profile overstates this share, at 13.5–16.9%: reading the clock waits for
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

    /** The number of nodes that create the events. It only has to exceed the number of parents of an event. */
    private static final int NUMBER_OF_NODES = 10;

    /** The number of other parents of every event, the default {@code maxOtherParents} of event creation. */
    private static final int OTHER_PARENTS = 4;

    /** The size of a transaction in bytes. It does not change the cost: the validator reads only the length. */
    private static final int TRANSACTION_SIZE = 100;

    /**
     * The transaction limits of the execution layer's defaults: {@code transaction.maxBytes} and
     * {@code transaction.maxTransactionBytesPerEvent} in {@code HederaConfig}.
     */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(6_144, 33_554_432);

    /** The number of transactions of every event. */
    @Param({"0", "1"})
    public int transactionsPerEvent;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The validator under test. */
    private DefaultEventFieldValidator validator;

    /** The generated events, as the intake receives them: parsed and hashed. */
    private List<PlatformEvent> receivedEvents;

    /** The copies of the received events that the validator checks in one invocation. */
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
        final Random random = new Random(SEED);

        // The first event of every node has no parents. It is a parent only and is not validated.
        final PlatformEvent[] latestEvents = new PlatformEvent[NUMBER_OF_NODES];
        for (int node = 0; node < NUMBER_OF_NODES; node++) {
            latestEvents[node] = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(node))
                    .setAppTransactionCount(0)
                    .build();
        }

        final EventHasher hasher = new DefaultEventHasher();
        final List<Integer> otherNodes = new ArrayList<>(NUMBER_OF_NODES - 1);
        receivedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            final int creator = i % NUMBER_OF_NODES;
            otherNodes.clear();
            for (int node = 0; node < NUMBER_OF_NODES; node++) {
                if (node != creator) {
                    otherNodes.add(node);
                }
            }
            Collections.shuffle(otherNodes, random);
            final List<PlatformEvent> otherParents = new ArrayList<>(OTHER_PARENTS);
            for (int j = 0; j < OTHER_PARENTS; j++) {
                otherParents.add(latestEvents[otherNodes.get(j)]);
            }
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setSelfParent(latestEvents[creator])
                    .setOtherParents(otherParents)
                    .setAppTransactionCount(transactionsPerEvent)
                    .setTransactionSize(TRANSACTION_SIZE)
                    .build();
            latestEvents[creator] = event;
            receivedEvents.add(receive(event, hasher));
        }
        verifyInputs();

        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                NodeId.of(0),
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        validator = new DefaultEventFieldValidator(metrics, Time.getCurrent(), TRANSACTION_LIMITS);
    }

    /**
     * Turns a generated event into one as the intake receives it: serialized and parsed, as gossip does, and hashed.
     */
    private static PlatformEvent receive(final PlatformEvent event, final EventHasher hasher) {
        final Bytes serialized = GossipEvent.PROTOBUF.toBytes(event.getGossipEvent());
        final GossipEvent parsed;
        try {
            parsed = GossipEvent.PROTOBUF.parse(serialized);
        } catch (final ParseException e) {
            throw new IllegalStateException("Failed to parse a generated event", e);
        }
        return hasher.hashEvent(new PlatformEvent(parsed, EventOrigin.GOSSIP));
    }

    /**
     * Verifies that every event has the parents and transactions that set the validator's work, so that a change to
     * the event builder cannot change the work unnoticed.
     */
    private void verifyInputs() {
        for (final PlatformEvent event : receivedEvents) {
            final GossipEvent gossipEvent = event.getGossipEvent();
            if (gossipEvent.parents().size() != 1 + OTHER_PARENTS
                    || gossipEvent.transactions().size() != transactionsPerEvent) {
                throw new IllegalStateException("Expected events with %d parents and %d transactions, but got %d and %d"
                        .formatted(
                                1 + OTHER_PARENTS,
                                transactionsPerEvent,
                                gossipEvent.parents().size(),
                                gossipEvent.transactions().size()));
            }
        }
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Prepares the benchmark invocation by copying the received events. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final PlatformEvent receivedEvent : receivedEvents) {
            events.add(receivedEvent.copyGossipedData());
        }
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
