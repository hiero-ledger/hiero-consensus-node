// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.core.jmh;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 10)
public class ConsensusBenchmark {
    @Param({"39"})
    public int numNodes;

    @Param({"100000"})
    public int numEvents;

    @Param({"0"})
    public long seed;

    private List<EventImpl> events;
    private Consensus consensus;

    @Setup(Level.Iteration)
    public void setup() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(seed)
                .setNumNodes(numNodes)
                .setWeightGenerator(WeightGenerators.BALANCED)
                .build();
        events = emitter.emitEvents(numEvents);

        consensus = new ConsensusImpl(
                platformContext,
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
           Results on a M1 Max MacBook Pro:
           Benchmark                              (numEvents)  (numNodes)  (seed)  Mode  Cnt   Score    Error  Units
           ConsensusBenchmark.calculateConsensus       100000          39       0  avgt    3  27.551 ± 11.690  ms/op
        */
    }
}
