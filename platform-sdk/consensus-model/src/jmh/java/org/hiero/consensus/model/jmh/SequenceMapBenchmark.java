// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.jmh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
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
 * Measures the latency of {@link StandardSequenceMap} as the components on the event path use it: adding an event
 * after looking up its parents, and moving the window forward, which removes the events that became ancient.
 *
 * <p>A {@link SequenceMap} is a map whose keys each have a sequence number, and which keeps only the keys whose
 * sequence numbers fall into a window. In production, the linker, deduplication, the orphan buffer, and the tipset
 * tracker each keep one, keyed by the {@link EventDescriptorWrapper} of an event, whose sequence number is the event's
 * birth round. For every event, the orphan buffer and the tipset tracker look up each of the event's parents and then
 * add the event; the linker and deduplication only add it. After every consensus round, each of them moves the window
 * to the round's ancient threshold with {@link SequenceMap#shiftWindow(long)}, which removes the events of the birth
 * rounds below it. The benchmark measures one such map.
 *
 * <p>{@link FalconRecorder} simulates a network of {@link #numNodes} nodes once per trial, on the ping times of
 * mainnet, and records one of its nodes. The benchmark replays the events that its orphan buffer released, its own
 * included, in that order, and between them the ancient threshold of each consensus round's event window. How many
 * events share a birth round and how far the window moves thus come from the simulated network, not from chosen
 * numbers. Each recorded event went through the gossip streams, so a parent lookup compares two different descriptor
 * objects in full, as in production, where the parent's descriptor in a received event is never the object that the
 * map holds. The hash code of every descriptor is computed once in the setup: in production, the first component that
 * uses a descriptor computes it, and the others find it cached.
 *
 * <p>For each birth round in its window, the map keeps the set of its keys, and it reuses these sets in turn as the
 * window moves. A set's table grows when keys are added and keeps its size when the set is cleared, so in a node that
 * has run for a while, adding an event does not grow a table. To measure that state, the map is created once per
 * trial, with the initial capacity of the maps of the linker, deduplication, and the orphan buffer, and the whole
 * recording is replayed into it once. Each invocation clears the map, which keeps the tables, and adds the events up
 * to the first window that removes events, outside the measured region. From then on, the map holds the non-ancient
 * events. Each benchmark method replays all the rest and times one kind of call:
 * <ul>
 *   <li>{@link #addEvent()} times every event: {@link SequenceMap#get(Object)} for each of its parents, then
 *   {@link SequenceMap#put(Object, Object)};</li>
 *   <li>{@link #shiftWindow()} times every call of {@link SequenceMap#shiftWindow(long)}, including those after a
 *   round that leaves the ancient threshold unchanged.</li>
 * </ul>
 * Each method runs the other calls of the replay paused ({@link LatencyRecorder#pause()}), so they count neither in
 * its latencies nor in its allocation.
 *
 * <p>Each latency includes one clock read ({@link LatencyRecorder#lap()}). On an Apple M4 laptop, one more clock read
 * after each event added about 17 ns, 10% of the latency of {@link #addEvent()}. A call of {@link #shiftWindow()} takes
 * over a microsecond on average, so the clock read is about 1% of it or less. The read costs nearly the same for every
 * call, so it does not hide a regression, but it makes one look smaller: by about a tenth for {@link #addEvent()}.
 *
 * <p>The recording runs the event creator and the consensus code, so a change to them can change the events. The
 * check after every pass then fails, and the expected values change in the same pull request.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per timed call. JMH's primary score
 * is the time of a whole pass, the paused calls included; the number of timed calls per pass differs between the
 * parameter sets. Each invocation's setup ends with {@code System.gc()}; a pass during which a garbage collection runs
 * is discarded and replaced by the next invocation. The heap is sized for the recording of 28 nodes: in a local heap
 * sweep, it needed 10 full collections at 2 GB and none at 3 GB.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
@Fork(
        value = 5,
        jvmArgsAppend = {"-Xms3g", "-Xmx3g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 10)
public class SequenceMapBenchmark {

    /** The seed of the recording. The same seed gives the same recording. */
    private static final long SEED = 0;

    /**
     * The number of events the recorded node releases from its orphan buffer, its own included, before the recording
     * ends. In recordings five to eight times as long, the map's average size and the average number of events that a
     * window move removes differ from those of this one by about 1%.
     */
    private static final int EVENTS = 50_000;

    /** The initial capacity of the maps of the linker, deduplication, and the orphan buffer, in birth rounds. */
    private static final int MAP_CAPACITY = 1024;

    /** The number of nodes in the simulated network. */
    @Param({"10", "28"})
    public int numNodes;

    /** What one call of the replay does. */
    private enum Kind {
        /** Looks up the parents of an event, then adds the event. */
        ADD_EVENT,
        /** Moves the window to a new ancient threshold. */
        SHIFT_WINDOW
    }

    /**
     * One call of the replay.
     *
     * @param kind             what the call does
     * @param event            the event to add, for {@link Kind#ADD_EVENT}
     * @param ancientThreshold the new start of the window, for {@link Kind#SHIFT_WINDOW}
     */
    private record Call(Kind kind, PlatformEvent event, long ancientThreshold) {}

    /**
     * The state of the map after a pass.
     *
     * @param foundParents the number of parents that the lookups after the fill found
     * @param size         the number of events in the map
     * @param windowStart  the start of the map's window
     */
    private record Result(long foundParents, int size, long windowStart) {}

    /** The calls of the replay, in order. */
    private Call[] calls;

    /** The number of calls that each invocation's setup makes: those before the first window that removes events. */
    private int fillCalls;

    /** The map, created once per trial and cleared by every invocation. */
    private SequenceMap<EventDescriptorWrapper, PlatformEvent> map;

    /** The number of parents that the lookups found since the counter was last reset. */
    private long foundParents;

    /** The latency recorder of the invocation. */
    private LatencyRecorder recorder;

    /**
     * Records the network, turns what the recorded node's orphan buffer released and the event windows into the calls
     * of the replay, and creates the map with the tables of a map that has run for a while. This method is called once
     * per trial.
     */
    @Setup(Level.Trial)
    public void recordCalls() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, EVENTS).build());
        calls = buildCalls(recording.nodes().getFirst().steps()).toArray(new Call[0]);
        fillCalls = firstMovingWindow();

        map = new StandardSequenceMap<>(0, MAP_CAPACITY, true, EventDescriptorWrapper::birthRound);
        for (final Call call : calls) {
            execute(call);
        }
    }

    /**
     * Turns the released events and the event windows into calls, and computes the hash codes of the descriptors of
     * the events and of their parents, which the descriptors cache.
     *
     * @param steps the steps of the recorded node
     * @return the calls, in order
     */
    private static List<Call> buildCalls(final List<Step> steps) {
        final List<Call> result = new ArrayList<>();
        for (final Step step : steps) {
            switch (step) {
                case Released released -> {
                    final PlatformEvent event = released.event();
                    event.getDescriptor().hashCode();
                    for (final EventDescriptorWrapper parent : event.getAllParents()) {
                        parent.hashCode();
                    }
                    result.add(new Call(Kind.ADD_EVENT, event, 0));
                }
                case NewEventWindow window ->
                    result.add(new Call(Kind.SHIFT_WINDOW, null, window.window().ancientThreshold()));
                default -> {
                    // received events, creation attempts, and platform status changes do not reach the map
                }
            }
        }
        return result;
    }

    /**
     * Clears the map and makes the calls up to the first window that removes events. This method is called once per
     * invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        map.clear();
        for (int i = 0; i < fillCalls; i++) {
            execute(calls[i]);
        }
        foundParents = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies the invocation: the lookups found the expected number of parents, and the map ends with the expected
     * size and window. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        final Result actual = new Result(foundParents, map.getSize(), map.getFirstSequenceNumberInWindow());
        final Result expected = expectedResult();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Expected %s, but got %s".formatted(expected, actual));
        }
        recorder = null;
    }

    /** The state of the map after a pass. It depends on the recording. */
    private Result expectedResult() {
        return switch (numNodes) {
            case 10 -> new Result(241_184, 1_420, 939);
            case 28 -> new Result(217_893, 4_720, 270);
            default -> throw new IllegalArgumentException("No expected result for numNodes=" + numNodes);
        };
    }

    /**
     * Replays the calls after the fill and records the latency of each event's parent lookups and addition.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void addEvent() {
        replay(Kind.ADD_EVENT);
    }

    /**
     * Replays the calls after the fill and records the latency of each call of {@link SequenceMap#shiftWindow(long)}.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void shiftWindow() {
        replay(Kind.SHIFT_WINDOW);
    }

    /**
     * Replays the calls after the fill, times the calls of the measured kind, and pauses the recorder for the others.
     *
     * @param measured the kind of call to time
     */
    private void replay(final Kind measured) {
        recorder.startMeasurement();
        recorder.pause();
        for (int i = fillCalls; i < calls.length; i++) {
            final Call call = calls[i];
            if (call.kind() == measured) {
                if (recorder.isPaused()) {
                    recorder.resume();
                }
                execute(call);
                recorder.lap();
            } else {
                if (!recorder.isPaused()) {
                    recorder.pause();
                }
                execute(call);
            }
        }
        if (recorder.isPaused()) {
            recorder.resume();
        }
        recorder.stopMeasurement();
    }

    /** Calls the map as the recorded call does. */
    private void execute(final Call call) {
        if (call.kind() == Kind.ADD_EVENT) {
            final PlatformEvent event = call.event();
            for (final EventDescriptorWrapper parent : event.getAllParents()) {
                if (map.get(parent) != null) {
                    foundParents++;
                }
            }
            map.put(event.getDescriptor(), event);
        } else {
            map.shiftWindow(call.ancientThreshold());
        }
    }

    /** Returns the index of the first window that moves past the genesis window, and so removes events. */
    private int firstMovingWindow() {
        final long genesisThreshold = EventWindow.getGenesisEventWindow().ancientThreshold();
        for (int i = 0; i < calls.length; i++) {
            if (calls[i].kind() == Kind.SHIFT_WINDOW && calls[i].ancientThreshold() > genesisThreshold) {
                return i;
            }
        }
        throw new IllegalStateException("No window of the recording moves past the genesis window");
    }
}
