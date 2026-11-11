// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;
import static org.hiero.consensus.benchmark.tools.event.EventShape.MAINNET;
import static org.hiero.consensus.benchmark.tools.event.EventStreamGenerator.receiveUnhashed;
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
 * THROWAWAY PROBE for <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/27940">#27940</a>, never
 * merged: a copy of {@link EventHashingBenchmark} that tests where its per-fork latency levels come from. Its four
 * subclasses run the same code and differ only in the JVM flags of their forks:
 * <ul>
 *   <li>{@link Baseline}: the flags of {@link EventHashingBenchmark};</li>
 *   <li>{@link Batch}: {@code -Xbatch}, so the JIT compiler compiles in the same order and with the same profiles in
 *   every fork;</li>
 *   <li>{@link Serial}: {@code -XX:+UseSerialGC}, so the garbage collector moves the objects on one thread and at
 *   points that depend only on the allocation;</li>
 *   <li>{@link BatchSerial}: both. The benchmark runs on one thread, so the forks then differ only in their addresses
 *   and in their physical memory.</li>
 * </ul>
 * The flags are diagnostics: they change what is measured, so only the spread between the forks of a variant counts,
 * not its level. If the levels stay with both flags, the cause lies outside the JVM's own state; if they go, the
 * variants with one flag tell whether the compiled code or the placement of the objects sets them.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public abstract class EventHashingForkProbe {

    /** The seed of the random number generator. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of events hashed in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The number of nodes that create the events. It only has to exceed the number of parents of an event. */
    private static final int NUMBER_OF_NODES = 28;

    /**
     * The SHA-256 digest of the hashes of all events, in order, by the number of transactions per event. A change to
     * the generated events or to the hashing changes it.
     */
    private static final Map<Integer, String> EXPECTED_DIGESTS = Map.of(
            0, "31f56f3f0726e1cbd1b4b5f8342c84b93f125e0642c208a18edca1fac1301a2e",
            2, "d3f80b137c1f39914da40535b74145c12ed097c6c4980bf04d9046514de958fb");

    /** The number of transactions of every event. */
    @Param({"0", "2"})
    public int transactionsPerEvent;

    /** The hasher under test. */
    private EventHasher hasher;

    /** The generated events, as their creators created them. */
    private List<PlatformEvent> generatedEvents;

    /** The events that the hasher hashed in one invocation, kept to calculate the digest for verification. */
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

    /** The forks of {@link EventHashingBenchmark}, as the reference. */
    @Fork(
            value = 10,
            jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
    public static class Baseline extends EventHashingForkProbe {}

    /** Forks without background compilation. */
    @Fork(
            value = 10,
            jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-Xbatch"})
    public static class Batch extends EventHashingForkProbe {}

    /** Forks with the serial garbage collector. */
    @Fork(
            value = 10,
            jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-XX:+UseSerialGC"})
    public static class Serial extends EventHashingForkProbe {}

    /** Forks without background compilation and with the serial garbage collector. */
    @Fork(
            value = 10,
            jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-Xbatch", "-XX:+UseSerialGC"})
    public static class BatchSerial extends EventHashingForkProbe {}
}
