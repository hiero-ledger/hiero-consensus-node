// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.jmh;

import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;
import static org.hiero.consensus.benchmark.tools.event.EventShape.MAINNET;
import static org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream.createSyncInputStream;
import static org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream.createSyncOutputStream;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRoster;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
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
 * Measures the latency of sending and receiving one event over a gossip connection: {@code write} is one
 * {@link SyncOutputStream#writePbjRecord} of a {@link GossipEvent}, as the writer thread of a peer connection sends it,
 * and {@code read} is one {@link SyncInputStream#readPbjRecord} of a {@link GossipEvent}, as the reader thread of a
 * peer connection receives it. In production, the two run on different threads of different nodes, so each method
 * measures one of them alone.
 *
 * <p>The streams are created as production creates them for a connection, with the default configuration: without
 * compression, which mainnet does not use, and with a buffer of the default {@code socket.bufferSize}. The output
 * stream writes into a sink that discards the bytes, and the input stream reads from the bytes in memory, so the socket
 * and its encryption are not measured. Like the stream of a connection, the output stream lives for the whole trial;
 * the input stream is new for every pass, because it is read to its end.
 *
 * <p>The cost grows with the size of an event: its parents, its transactions, and its signature. The events take their
 * shape from mainnet's ({@link EventShape#MAINNET}): a self parent and the median number of other parents, four, and
 * transactions of the median size, 470 bytes. The number of transactions per event is a parameter, at two quantiles of
 * mainnet's events: 0, the median (96% of the events carry none), and 2, the 99th percentile. The events are unsigned:
 * their signatures are random bytes of the length of the RSA signatures that mainnet's events carry, 384 bytes, which
 * is all that matters here. Every hash is a SHA-256 hash, as in production after the event cutover.
 *
 * <p>{@link EventStreamGenerator} generates the events once per trial from a fixed seed, as their creators created
 * them: {@code write} writes them as a node sends its own events to its peers, and {@code read} reads them as the peers
 * receive them. The SHA-256 digest of their serialized form must equal a constant; it changes when the generated events
 * change.
 *
 * <p>{@code write} writes the same events in every pass. The records compute their encoded size on first use and keep
 * it. Production writes the same record to every peer, so only the write to the first peer computes the size; this
 * benchmark measures the writes that follow. After every pass, the stream must have written exactly as many bytes as
 * the serialized form of all events has.
 *
 * <p>{@code read} reads the serialized form of all events from a new stream in every pass, and every event it reads
 * must equal the generated event.
 *
 * <p>One invocation, a pass, writes or reads {@value #NUMBER_OF_EVENTS} events. The primary score is the mean time per
 * event. A {@link LatencyRecorder} also times every call but the first of {@code read}, with one timestamp per call
 * ({@link LatencyRecorder#lap()}). {@link LatencyProfiler} reports the percentiles and the allocation per call. The
 * allocation is measured around the timed calls only, because {@code -prof gc} also counts the setup.
 *
 * <p>The first call of a {@code read} pass, right after the setup, reads an event but is not timed. It is several times
 * slower than the others, and one call in {@value #NUMBER_OF_EVENTS} is exactly the share above p99.99, so timing it
 * would put the setup into p99.99. No such setup precedes an event in production. {@code write} has no setup before
 * its passes, and its first call is as fast as the others, so it times every call.
 *
 * <p>Writing or reading an event takes so little time that the timing is a noticeable part of every latency. The share
 * is about constant, so a regression still shows in full nanoseconds, but as a slightly smaller percentage.
 *
 * <p>{@code read} allocates the events it reads, so each of its invocations' setups ends with {@code System.gc()}, and
 * usually no garbage collection falls into the measured calls. {@code write} allocates nothing, neither in its calls
 * nor in its setup, so it needs no collection. A pass during which one runs is discarded and replaced by the next
 * invocation, so the latency results and {@code latency.mean} exclude garbage collection. JMH's primary score, measured
 * by JMH itself, still includes discarded passes.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public class GossipEventSerializationBenchmark {

    /** The seed of the random number generators. The same seed produces the same roster and events. */
    private static final long SEED = 0;

    /** The number of events written or read in one invocation. */
    private static final int NUMBER_OF_EVENTS = 10_000;

    /** The number of nodes that create the events: 32, as in the other consensus-layer benchmarks. */
    private static final int NUMBER_OF_NODES = 32;

    /**
     * The SHA-256 digest of the serialized form of all events, by the number of transactions per event. A change to
     * the generated events changes it.
     */
    private static final Map<Integer, String> EXPECTED_DIGESTS = Map.of(
            0, "c291032c9a54ecaf691f8b55ec779661204ccc6f94f7f0edcc6a568ffaafe7ab",
            2, "2c1513343e60e542e3e00dada9c331d3f0caba16dd3c6a2cb8e069cacb064d2b");

    /** The state of {@code write}: the events and the stream they are written to. */
    @State(Scope.Thread)
    public static class Writer {

        /** The number of transactions of every event. */
        @Param({"0", "2"})
        public int transactionsPerEvent;

        /** The events, written in every pass. */
        private GossipEvent[] events;

        /** The number of bytes that one pass writes. */
        private long expectedBytes;

        /** The stream under test. Like the stream of a connection, it lives for the whole trial. */
        private SyncOutputStream output;

        /** The number of bytes the stream had written before the pass. */
        private long bytesBefore;

        /** The latency recorder for the benchmark. */
        private LatencyRecorder recorder;

        /**
         * Generates the events and creates the stream under test. This method is called once per trial.
         */
        @Setup(Level.Trial)
        public void setupTrial() {
            final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
            final int bufferSize =
                    configuration.getConfigData(SocketConfig.class).bufferSize();

            events = generateEvents(transactionsPerEvent);
            expectedBytes = serialize(events).length;
            output = createSyncOutputStream(configuration, OutputStream.nullOutputStream(), bufferSize);

            // Collect the garbage of the setup now, so that it is not collected during the measured calls.
            System.gc();
        }

        /**
         * Notes the bytes written so far. This method is called once per invocation. It allocates nothing, so it needs
         * no garbage collection.
         */
        @Setup(Level.Invocation)
        public void prepareInvocation() {
            bytesBefore = output.connectionByteCounter().getTotalCount();
            recorder = LatencyProfiler.recorder();
        }

        /**
         * Verifies that the pass wrote all events. This method is called once per invocation.
         *
         * @throws IOException if the stream fails
         */
        @TearDown(Level.Invocation)
        public void verifyInvocation() throws IOException {
            // Production flushes when the connection has nothing more to send.
            output.flush();
            final long actual = output.connectionByteCounter().getTotalCount() - bytesBefore;
            if (actual != expectedBytes) {
                throw new IllegalStateException(
                        "Expected the pass to write %d bytes, but it wrote %d".formatted(expectedBytes, actual));
            }
            recorder = null;
        }
    }

    /** The state of {@code read}: the serialized events and the stream they are read from. */
    @State(Scope.Thread)
    public static class Reader {

        /** The number of transactions of every event. */
        @Param({"0", "2"})
        public int transactionsPerEvent;

        /** The configuration that the streams are created with. */
        private Configuration configuration;

        /** The generated events, which the events read must equal. */
        private GossipEvent[] expectedEvents;

        /** The serialized form of all events, read in every pass. */
        private byte[] serializedEvents;

        /** The stream under test, new for every pass. */
        private SyncInputStream input;

        /** The events read in one pass. */
        private GossipEvent[] events;

        /** The latency recorder for the benchmark. */
        private LatencyRecorder recorder;

        /**
         * Generates and serializes the events. This method is called once per trial.
         */
        @Setup(Level.Trial)
        public void setupTrial() {
            configuration = new TestConfigBuilder().getOrCreateConfig();
            expectedEvents = generateEvents(transactionsPerEvent);
            serializedEvents = serialize(expectedEvents);
            events = new GossipEvent[NUMBER_OF_EVENTS];
        }

        /**
         * Creates a new stream over the serialized events for the pass. This method is called once per invocation.
         */
        @Setup(Level.Invocation)
        public void prepareInvocation() {
            final int bufferSize =
                    configuration.getConfigData(SocketConfig.class).bufferSize();
            input = createSyncInputStream(configuration, new ByteArrayInputStream(serializedEvents), bufferSize);
            recorder = LatencyProfiler.recorder();

            // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during
            // the measured calls.
            System.gc();
        }

        /**
         * Verifies that the pass read every event as it was generated and all bytes. This method is called once per
         * invocation.
         *
         * @throws IOException if the stream fails
         */
        @TearDown(Level.Invocation)
        public void verifyInvocation() throws IOException {
            for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
                if (!expectedEvents[i].equals(events[i])) {
                    throw new IllegalStateException(
                            "Expected event %d to be %s, but read %s".formatted(i, expectedEvents[i], events[i]));
                }
            }
            if (input.read() != -1) {
                throw new IllegalStateException("The pass did not read all bytes");
            }
            Arrays.fill(events, null);
            input = null;
            recorder = null;
        }
    }

    /**
     * Writes all events and records the latency of each call.
     *
     * @param writer the events and the stream
     * @throws IOException if the stream fails
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void write(final Writer writer) throws IOException {
        final SyncOutputStream output = writer.output;
        final GossipEvent[] events = writer.events;
        final LatencyRecorder recorder = writer.recorder;
        recorder.startMeasurement();
        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            output.writePbjRecord(events[i], GossipEvent.PROTOBUF);
            // One timestamp per call: each latency also contains the loop step to the next event.
            recorder.lap();
        }
        recorder.stopMeasurement();
    }

    /**
     * Reads all events and records the latency of each call but the first.
     *
     * @param reader the serialized events and the stream
     * @throws IOException if the stream fails
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void read(final Reader reader) throws IOException {
        final SyncInputStream input = reader.input;
        final GossipEvent[] events = reader.events;
        final LatencyRecorder recorder = reader.recorder;
        recorder.startMeasurement();
        // The first call follows the setup, which makes it slow; it reads an event but is not timed.
        recorder.pause();
        events[0] = input.readPbjRecord(GossipEvent.PROTOBUF);
        recorder.resume();
        for (int i = 1; i < NUMBER_OF_EVENTS; i++) {
            events[i] = input.readPbjRecord(GossipEvent.PROTOBUF);
            // One timestamp per call: each latency also contains the loop step to the next event.
            recorder.lap();
        }
        recorder.stopMeasurement();
    }

    /**
     * Generates the events from the fixed seed and verifies that their digest equals the expected one.
     *
     * @param transactionsPerEvent the number of transactions of every event
     * @return the events, in the order they were generated
     */
    private static GossipEvent[] generateEvents(final int transactionsPerEvent) {
        final EventShape shape = new EventShape(
                constant(MAINNET.otherParents().median()),
                constant(transactionsPerEvent),
                constant(MAINNET.transactionSize().median()));
        // Every event birth round is past the cutover, so every event is hashed with SHA-256.
        EventHashFactory.initialize(0);
        final RosterWrapper roster = randomRoster(new Random(SEED), NUMBER_OF_NODES);
        final List<PlatformEvent> generatedEvents =
                EventStreamGenerator.generateUnsignedEvents(shape, roster, NUMBER_OF_EVENTS, SEED);

        final GossipEvent[] events = new GossipEvent[NUMBER_OF_EVENTS];
        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            events[i] = generatedEvents.get(i).getGossipEvent();
        }
        verifyDigest(events, transactionsPerEvent);
        return events;
    }

    /**
     * Verifies that the digest of the serialized events equals the expected one, so that a change to the generated
     * events cannot change the work unnoticed.
     *
     * @param events               the events
     * @param transactionsPerEvent the number of transactions of every event
     */
    private static void verifyDigest(final GossipEvent[] events, final int transactionsPerEvent) {
        final String actual;
        try {
            actual = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(events)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final String expected = EXPECTED_DIGESTS.get(transactionsPerEvent);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Expected the digest of the serialized events %s, but got %s".formatted(expected, actual));
        }
    }

    /**
     * Serializes the events as {@link SyncOutputStream#writePbjRecord} writes them, each as its size in four bytes
     * followed by its protobuf encoding, without using the stream.
     *
     * @param events the events
     * @return the serialized events
     */
    private static byte[] serialize(final GossipEvent[] events) {
        final List<Bytes> encodings = new ArrayList<>(events.length);
        int size = 0;
        for (final GossipEvent event : events) {
            final Bytes encoding = GossipEvent.PROTOBUF.toBytes(event);
            encodings.add(encoding);
            size += Integer.BYTES + (int) encoding.length();
        }
        final ByteBuffer buffer = ByteBuffer.allocate(size);
        for (final Bytes encoding : encodings) {
            buffer.putInt((int) encoding.length());
            buffer.put(encoding.toByteArray());
        }
        return buffer.array();
    }
}
