// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.core.jmh;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 3)
@Measurement(iterations = 3, time = 10)
public class ConsensusBenchmark {
    private static final long SEED = 0;

    @Param({"4", "10", "30"})
    public int numNodes;

    @Param({"1", "2", "4"})
    public int numOP;

    @Param({"100000"})
    public int numEvents;

    private List<EventImpl> events;
    private Consensus consensus;

    @Setup(Level.Invocation)
    public void setup() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(SEED)
                .setNumNodes(numNodes)
                .setMaxOtherParents(numOP)
                .setWeightGenerator(WeightGenerators.BALANCED)
                .build();
        events = emitter.emitEvents(numEvents);

        // We pass the events through the orphan buffer so that their nGen is set
        final DefaultOrphanBuffer orphanBuffer =
                new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
        for (final EventImpl event : events) {
            final List<PlatformEvent> obOut = orphanBuffer.handleEvent(event.getPlatformEvent());
            if (obOut.size() != 1) {
                throw new IllegalStateException("Orphan buffer should not be producing orphans in this benchmark");
            }
        }

        consensus = new ConsensusImpl(
                platformContext.getConfiguration(),
                platformContext.getTime(),
                new NoOpConsensusMetrics(),
                emitter.getGraphGenerator().getRoster());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void calculateConsensus(final Blackhole bh) {
        for (final EventImpl event : events) {
            bh.consume(consensus.addEvent(event));
        }

        /*
        Benchmark                              (numEvents)  (numNodes)  (numOP)  Mode  Cnt     Score     Error  Units
        ConsensusBenchmark.calculateConsensus       100000           4        1  avgt    3   334.490 ±  43.182  ms/op
        ConsensusBenchmark.calculateConsensus       100000           4        2  avgt    3   400.108 ± 112.360  ms/op
        ConsensusBenchmark.calculateConsensus       100000           4        4  avgt    3   445.310 ± 264.019  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        1  avgt    3   761.099 ± 233.237  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        2  avgt    3   801.404 ± 214.349  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        4  avgt    3   979.868 ± 270.677  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        1  avgt    3  2895.844 ±  71.784  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        2  avgt    3  2893.139 ± 390.462  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        4  avgt    3  3621.163 ± 530.187  ms/op
        */
    }
}
