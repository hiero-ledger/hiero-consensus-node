package org.hiero.consensus.hashgraph;

import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.PlatformEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
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
public class HashgraphModuleBenchmark {
    private static final long SEED = 0;
    private static final long NUMBER_OF_EVENTS = 2_000_000;

    @Param({"4", "10", "30"})
    public int numNodes;

    @Param({"1", "2", "4"})
    public int numOP;

    private HashgraphModule hashgraphModule;
    private PlatformEvent[] platformEvents;
    private int currentEventIndex;

    @Setup(Level.Iteration)
    public void setup() {
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .seed(SEED)
                .maxOtherParents(numOP)
                .realSignatures(false)
                .numNodes(numNodes)
                .setPopulateNgen(true)
                .build();
        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            platformEvents[i] = generator.next();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    //@OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void calculateConsensus(final Blackhole bh) {

    }
}
