// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;
import static org.hiero.consensus.benchmark.tools.event.EventShape.MAINNET;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRoster;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
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
 * Measures the latency of {@link DefaultEventHasher#hashEvent(PlatformEvent)}, which the intake runs first on every
 * received event. The hasher writes the event's core and its parents' descriptors into a SHA-256 digest. It hashes
 * every transaction separately with SHA-384, stores that hash on the transaction, and adds it to the event's digest, so
 * that a transaction can be removed later without changing the event's hash.
 *
 * <p>The cost grows with the number of parents and of transactions and with the size of the transactions. The events
 * take their shape from mainnet's ({@link EventShape#MAINNET}): a self parent and the median number of other parents,
 * four, and transactions of the median size, 470 bytes. The number of transactions per event is a parameter, at two
 * quantiles of mainnet's events: 0, the median (96% of the events carry none), and 2, the 99th percentile.
 *
 * <p>{@link EventStreamGenerator} generates the events once per trial from a fixed seed. They are unsigned, because the
 * hash does not cover the signature. Every hash is a SHA-256 hash, as in production after the event cutover.
 *
 * <p>The intake hashes an event first, right after it has received it, with the event's data still in the CPU caches.
 * So the benchmark receives every event anew right before it hashes it, as a node receives it through gossip but
 * unhashed ({@link EventStreamGenerator#receiveUnhashed(List)}). Hashing the same objects in every pass could reuse
 * what the previous pass left in them, such as the encoded size that a PBJ record keeps after its first use.
 *
 * <p>After every invocation, the SHA-256 digest of the hashes of all events, in order, must equal a constant. It
 * changes when the events or the hashing change.
 *
 * <p>One invocation, a pass, receives and hashes {@value #NUMBER_OF_EVENTS} events. A {@link LatencyRecorder} is paused
 * while an event is received, so the receiving counts neither in a latency nor in the allocation. It times each hash
 * from {@link LatencyRecorder#resume()} right before the call to {@link LatencyRecorder#lap()} right after it, and
 * {@link LatencyProfiler} reports the percentiles and the allocation per hash. JMH's primary score, the mean time per
 * event of the whole pass, includes the receiving; track {@code latency.mean} instead.
 *
 * <p>The first call of a pass comes right after the setup and is much slower than the others, so it is hashed but not
 * timed. On an Apple M4 laptop, its median was 3.0–3.3 µs without transactions and 4.1–4.5 µs with two, against about
 * 0.38 µs and 1.0 µs for the other calls. One call in {@value #NUMBER_OF_EVENTS} is exactly the share above p99.99, so
 * timing the first call of every pass would set p99.99. Without transactions, p99.99 was 3.1–8.6 µs when the first
 * call was timed and 0.8–1.1 µs when it was not, in the same run; with two transactions, timing it made no measurable
 * difference, because other calls are as slow. No such setup precedes an event in production.
 *
 * <p>Each latency also contains one reading of the clock, which is a noticeable part of a call this short. On an Apple
 * M4 laptop, an empty call, {@code resume()} directly followed by {@code lap()}, took 11.2–11.3 ns on average, about 3%
 * of a hash without transactions and 1% of one with two; on the benchmark machine it is unmeasured. The share is about
 * constant, so a regression of the hasher still shows in full nanoseconds, but as a slightly smaller percentage.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the pass,
 * although receiving allocates several kilobytes per event. A pass during which one does is discarded and replaced by
 * the next invocation, so the latency results and {@code latency.mean} exclude garbage collection.
 *
 * <p>Each fork settles at its own latency level and keeps it. On the benchmark machine, {@code latency.mean} varies by
 * 1.9% between forks without transactions and by 0.7% with two, against 0.07% and 0.18% between the iterations of one
 * fork, although every fork allocates the same and the machine was quiet. The difference is about the same in
 * nanoseconds in both parameter sets. Its cause is unknown
 * (<a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/27940">#27940</a>), and it sets the number of
 * forks.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-28, bf9d6a86f4</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms1g -Xmx1g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: transactionsPerEvent=0 / transactionsPerEvent=2</li>
 *   <li>Steady state after: 5 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.07% / 0.18% per 10 s iteration, 1.92% / 0.66% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 1.18% / 0.41% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 2.9 M / 1.7 M; expected stable: mean, p50, p90, p99, p99.99 / mean, p50, p90, p99.9, p99.99</li>
 *   <li>Runtime: 5.1 / 5.1 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/40678159284, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/40687277605</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 10,
        jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public class EventHashingBenchmark {

    /** The seed of the random number generator. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of events hashed in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The number of nodes that create the events. It only has to exceed the number of parents of an event. */
    private static final int NUMBER_OF_NODES = 32;

    /**
     * The SHA-256 digest of the hashes of all events, in order, by the number of transactions per event. A change to
     * the generated events or to the hashing changes it.
     */
    private static final Map<Integer, String> EXPECTED_DIGESTS = Map.of(
            0, "9223171760a17caf361d0c52ce6a95071dc06107183a0026f254f57ff07a3c93",
            2, "9e2819c4a599199a7a6ef68ddbd64a58389f7831b5848c8715eb6e8c20da3bbb");

    /** The number of transactions of every event. */
    @Param({"0", "2"})
    public int transactionsPerEvent;

    /** The hasher under test. */
    private EventHasher hasher;

    /** The generated events, as their creators created them. */
    private List<PlatformEvent> generatedEvents;

    /** The events that the hasher hashed in one invocation, kept for the check after it. */
    private PlatformEvent[] events;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Generates the events and creates the hasher. This method is called once per trial.
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

        hasher = new DefaultEventHasher();
    }

    /**
     * Prepares the benchmark invocation. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = new PlatformEvent[NUMBER_OF_EVENTS];
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the previous invocation now, so that it is not collected during the measured calls.
        System.gc();
    }

    /**
     * Verifies the hashes of all events against the expected digest. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        final MessageDigest digest = DigestType.SHA_256.buildDigest();
        for (final PlatformEvent event : events) {
            final Hash hash = event.getHash();
            if (hash == null || hash.getDigestType() != DigestType.SHA_256) {
                throw new IllegalStateException("Expected a SHA-256 hash, but got " + hash);
            }
            digest.update(hash.copyToByteArray());
        }
        final String actual = HexFormat.of().formatHex(digest.digest());
        final String expected = EXPECTED_DIGESTS.get(transactionsPerEvent);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Expected the digest of the event hashes %s, but got %s".formatted(expected, actual));
        }
        events = null;
        recorder = null;
    }

    /**
     * Receives every event anew and hashes it right away, as the intake does, and records the latency of each hash but
     * the first. The recorder is paused while an event is received.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void hashEvents() {
        recorder.startMeasurement();
        recorder.pause();
        // The first call follows the setup, which makes it slow; it is hashed but not timed.
        events[0] = hasher.hashEvent(receiveUnhashed(generatedEvents.getFirst()));
        for (int i = 1; i < NUMBER_OF_EVENTS; i++) {
            final PlatformEvent event = receiveUnhashed(generatedEvents.get(i));
            recorder.resume();
            hasher.hashEvent(event);
            recorder.lap();
            recorder.pause();
            events[i] = event;
        }
        recorder.resume();
        recorder.stopMeasurement();
    }

    /** Receives a generated event anew, as the intake receives it: parsed from the gossip streams and built. */
    private static PlatformEvent receiveUnhashed(final PlatformEvent generatedEvent) {
        return EventStreamGenerator.receiveUnhashed(List.of(generatedEvent)).getFirst();
    }
}
