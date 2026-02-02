// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.core.jmh;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.Consensus;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
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

    private List<PlatformEvent> platformEvents;
    private List<EventImpl> linkedEvents;
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
        platformEvents = emitter.emitEvents(numEvents);

        // We pass the events through the orphan buffer so that their nGen is set
        final DefaultOrphanBuffer orphanBuffer =
                new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        for (final PlatformEvent event : platformEvents) {
            final List<PlatformEvent> obOut = orphanBuffer.handleEvent(event);
            if (obOut.size() != 1) {
                throw new IllegalStateException("Orphan buffer should not be producing orphans in this benchmark");
            }
            final EventImpl linkedEvent = consensusLinker.linkEvent(obOut.getFirst());
            if (linkedEvent == null) {
                throw new IllegalStateException("Linker should always link each event in this benchmark");
            }
            linkedEvents.add(linkedEvent);
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
        for (final EventImpl event : linkedEvents) {
            bh.consume(consensus.addEvent(event));
        }

        /*
        Results on a M1 Max MacBook Pro:

        Benchmark                              (numEvents)  (numNodes)  (numOP)  Mode  Cnt     Score     Error  Units
        ConsensusBenchmark.calculateConsensus       100000           4        1  avgt    3   371.516 ± 260.771  ms/op
        ConsensusBenchmark.calculateConsensus       100000           4        2  avgt    3   404.322 ± 140.380  ms/op
        ConsensusBenchmark.calculateConsensus       100000           4        4  avgt    3   417.989 ± 230.466  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        1  avgt    3   746.292 ±  97.510  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        2  avgt    3   840.145 ± 180.210  ms/op
        ConsensusBenchmark.calculateConsensus       100000          10        4  avgt    3   953.634 ± 393.661  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        1  avgt    3  2841.761 ± 523.974  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        2  avgt    3  3060.256 ± 430.140  ms/op
        ConsensusBenchmark.calculateConsensus       100000          30        4  avgt    3  3606.326 ± 276.873  ms/op
        */
    }
}
