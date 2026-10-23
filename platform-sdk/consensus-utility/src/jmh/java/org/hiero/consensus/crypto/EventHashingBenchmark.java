// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
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
 * <p>Every event has a self parent and {@value #OTHER_PARENTS} other parents, the most that the default
 * {@code maxOtherParents} of event creation allows. The number of transactions per event is a parameter: on mainnet,
 * events carry fewer than 0.1 transactions on average (metric {@code trans_per_event}), so most carry none. A
 * transaction has {@value #TRANSACTION_SIZE} bytes, the mainnet mean of the metric {@code bytes_per_trans} over the 30
 * days before October 2026.
 *
 * <p>Events hash with SHA-256 since the event cutover, which is enabled by default. The benchmark sets the cutover to the
 * first round, as a network that starts from genesis does, so every event and every parent's hash uses SHA-256.
 *
 * <p>The events are generated once per trial from a fixed seed and serialized. Every invocation parses them again into
 * new events, as gossip receives them, outside the measured region. The parsed records compute their encoded size on
 * first use and keep it, and in production the hasher is the first to use it; hashing the same events again would skip
 * that work.
 *
 * <p>After every invocation, the SHA-256 digest of the hashes of all events, in order, must equal a constant. It
 * changes when the events or the hashing change.
 *
 * <p>One invocation, a pass, hashes {@value #NUMBER_OF_EVENTS} events. The primary score is the mean time per event. A
 * {@link LatencyRecorder} also times every call but the first, with one timestamp per call
 * ({@link LatencyRecorder#lap()}). {@link LatencyProfiler} reports the percentiles and the allocation per call. The
 * allocation is measured around the timed calls only, because {@code -prof gc} also counts the parsing in the setup.
 *
 * <p>The first call of a pass, right after the setup, is hashed but not timed. On an Apple M4 laptop, its median was
 * 5.8–6.7 µs without transactions and 8.1–9.0 µs with one, against about 0.4 µs and 0.8 µs for the other calls. One
 * call in {@value #NUMBER_OF_EVENTS} is exactly the share above p99.99, so timing it would put the setup into p99.99;
 * without it, p99.99 was 4.0 µs and 4.4 µs. No such setup precedes an event in production.
 *
 * <p>A hash takes so little time that the timing is a noticeable part of every latency. On an Apple M4 laptop,
 * {@code lap()} added 12.2–14.7 ns to a call without transactions (3.0–3.6% of the timed call) and 14.4–15.8 ns to a
 * call with one (1.8–2.0%), measured with and without laps in chunks interleaved in the same run; on the benchmark
 * machine it is unmeasured. A CPU profile overstates this share, at 6.8–7.8%. The share is about constant, so a
 * regression of the hasher still shows in full nanoseconds, but as a slightly smaller percentage.
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
public class EventHashingBenchmark {

    /** The seed of the random number generator. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of events hashed in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The number of nodes that create the events. It only has to exceed the number of parents of an event. */
    private static final int NUMBER_OF_NODES = 10;

    /** The number of other parents of every event, the default {@code maxOtherParents} of event creation. */
    private static final int OTHER_PARENTS = 4;

    /** The size of a transaction in bytes, the mainnet mean. */
    private static final int TRANSACTION_SIZE = 445;

    /**
     * The SHA-256 digest of the hashes of all events, in order, by the number of transactions per event. A change to
     * the event builder or to the hashing changes it.
     */
    private static final Map<Integer, String> EXPECTED_DIGESTS = Map.of(
            0, "116abb7428503a8644ac763aff5f781bc647356829dcf5df065a13f1ddfad4d9",
            1, "1e960f80e7eef793182b5a2d15d59f25a84e7d922224b63bf067f38aa3e2ad37");

    /** The number of transactions of every event. */
    @Param({"0", "1"})
    public int transactionsPerEvent;

    /** The hasher under test. */
    private EventHasher hasher;

    /** The generated events, serialized as gossip sends them. */
    private List<Bytes> serializedEvents;

    /** The events that the hasher hashes in one invocation, parsed as gossip receives them. */
    private List<PlatformEvent> events;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Generates and serializes the events and creates the hasher. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void generateEvents() {
        // Every event is past the cutover: the parents get SHA-256 hashes, and the hasher uses SHA-256.
        EventHashFactory.initialize(ConsensusConstants.ROUND_FIRST);
        final Random random = new Random(SEED);

        // The first event of every node has no parents. It is a parent only and is not hashed.
        final PlatformEvent[] latestEvents = new PlatformEvent[NUMBER_OF_NODES];
        for (int node = 0; node < NUMBER_OF_NODES; node++) {
            latestEvents[node] = new TestingEventBuilder(random)
                    .setCreatorId(NodeId.of(node))
                    .setAppTransactionCount(0)
                    .build();
        }

        final List<Integer> otherNodes = new ArrayList<>(NUMBER_OF_NODES - 1);
        serializedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
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
            serializedEvents.add(GossipEvent.PROTOBUF.toBytes(event.getGossipEvent()));
        }
        verifyInputs();

        hasher = new DefaultEventHasher();
    }

    /**
     * Parses a serialized event, as gossip does.
     */
    private static GossipEvent parse(final Bytes serializedEvent) {
        try {
            return GossipEvent.PROTOBUF.parse(serializedEvent);
        } catch (final ParseException e) {
            throw new IllegalStateException("Failed to parse a generated event", e);
        }
    }

    /**
     * Verifies that every event has the parents and transactions that set the hasher's work, so that a change to the
     * event builder cannot change the work unnoticed.
     */
    private void verifyInputs() {
        for (final Bytes serializedEvent : serializedEvents) {
            final GossipEvent event = parse(serializedEvent);
            final boolean expectedParents = event.parents().size() == 1 + OTHER_PARENTS
                    && event.parents().stream()
                            .allMatch(parent -> parent.hash().length() == DigestType.SHA_256.digestLength());
            final boolean expectedTransactions = event.transactions().size() == transactionsPerEvent
                    && event.transactions().stream().allMatch(transaction -> transaction.length() == TRANSACTION_SIZE);
            if (!expectedParents || !expectedTransactions) {
                throw new IllegalStateException(
                        "Expected events with %d parents with SHA-256 hashes and %d transactions of %d bytes, but got %s"
                                .formatted(1 + OTHER_PARENTS, transactionsPerEvent, TRANSACTION_SIZE, event));
            }
        }
    }

    /**
     * Prepares the benchmark invocation by parsing the serialized events. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final Bytes serializedEvent : serializedEvents) {
            events.add(new PlatformEvent(parse(serializedEvent), EventOrigin.GOSSIP));
        }
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
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
     * Hashes all events and records the latency of each call but the first.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void hashEvents() {
        recorder.startMeasurement();
        // The first call follows the setup, which makes it slow; it is hashed but not timed.
        recorder.pause();
        hasher.hashEvent(events.getFirst());
        recorder.resume();
        for (int i = 1; i < NUMBER_OF_EVENTS; i++) {
            hasher.hashEvent(events.get(i));
            // One timestamp per call: each latency also contains the loop step to the next event.
            recorder.lap();
        }
        recorder.stopMeasurement();
    }
}
