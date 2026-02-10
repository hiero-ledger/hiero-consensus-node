// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.Consensus;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.model.event.PlatformEvent;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 3)
@Measurement(iterations = 3, time = 10)
public class ConsensusBenchmark {
    private static final long SEED = 0;
    private static final int NUMBER_OF_EVENTS = 100000;

    @Param({"4", "10"})
    public int numNodes;

    @Param({"1", "4"})
    public int numOP;

    private List<EventImpl> linkedEvents;
    private Consensus consensus;

    @Setup(Level.Invocation)
    public void setup() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .seed(SEED)
                .maxOtherParents(numOP)
                .realSignatures(false)
                .numNodes(numNodes)
                .setPopulateNgen(true)
                .build();
        final List<PlatformEvent> platformEvents = generator.nextEvents(NUMBER_OF_EVENTS);

        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        linkedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final PlatformEvent event : platformEvents) {
            final EventImpl linkedEvent = consensusLinker.linkEvent(event);
            if (linkedEvent == null) {
                throw new IllegalStateException("Linker should always link each event in this benchmark");
            }
            linkedEvents.add(linkedEvent);
        }

        consensus = new ConsensusImpl(
                platformContext.getConfiguration(),
                platformContext.getTime(),
                new NoOpConsensusMetrics(),
                generator.getRoster());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void calculateConsensus(final Blackhole bh) {
        for (final EventImpl event : linkedEvents) {
            bh.consume(consensus.addEvent(event));
        }

        /*
        Results on a M1 Max MacBook Pro:

        ConsensusBenchmark.calculateConsensus           4        1  thrpt    3  330818.730 ± 90113.698  ops/s
        ConsensusBenchmark.calculateConsensus           4        4  thrpt    3  266643.605 ± 50639.163  ops/s
        ConsensusBenchmark.calculateConsensus          10        1  thrpt    3  155049.428 ± 43977.153  ops/s
        ConsensusBenchmark.calculateConsensus          10        4  thrpt    3  123922.015 ± 59057.164  ops/s
        */
    }
}
