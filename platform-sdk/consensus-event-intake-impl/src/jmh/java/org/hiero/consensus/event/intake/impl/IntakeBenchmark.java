// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;


import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.fixtures.event.generator.SimpleGraphGenerator;
import com.swirlds.platform.test.fixtures.event.generator.SimpleGraphGeneratorBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;
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
public class IntakeBenchmark {
    @Param({"4", "10", "30"})
    public int numNodes;

    @Param({"100000"})
    public int numEvents;

    @Param({"0"})
    public long seed;

    private List<PlatformEvent> events;
    private DefaultEventIntakeModule intake;

    @Setup(Level.Invocation)
    public void setup() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final SimpleGraphGenerator generator = SimpleGraphGeneratorBuilder.builder()
                .numNodes(numNodes)
                .maxOtherParents(1)
                .seed(seed)
                .build();
        events = generator.generateEvents(numEvents);


        final WiringConfig wiringConfig = platformContext.getConfiguration().getConfigData(WiringConfig.class);

        final int coreCount = Runtime.getRuntime().availableProcessors();
        final int parallelism = (int)
                Math.max(1, wiringConfig.defaultPoolMultiplier() * coreCount + wiringConfig.defaultPoolConstant());
        final ForkJoinPool defaultPool =
                platformContext.getExecutorFactory().createForkJoinPool(parallelism);
        final WiringModel model = WiringModelBuilder.create(platformContext.getMetrics(), platformContext.getTime())
                .enableJvmAnchor()
                .withDefaultPool(defaultPool)
                .withWiringConfig(wiringConfig)
                .build();
        final RosterHistory rosterHistory =
                new RosterHistory(List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, generator.getRoster()));

        intake = new DefaultEventIntakeModule();
        intake.initialize(
                model,
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory,
                new NoOpIntakeEventCounter(),
                new TransactionLimits(1000,1000),
                new EventPipelineTracker(platformContext.getMetrics())
        );

    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void calculateConsensus(final Blackhole bh) {
        for (final PlatformEvent event : events) {

        }

        /*
            Results on a M1 Max MacBook Pro:
            Benchmark                              (numEvents)  (numNodes)  (seed)  Mode  Cnt     Score     Error  Units
            ConsensusBenchmark.calculateConsensus       100000           4       0  avgt    3   373.780 ± 214.697  ms/op
            ConsensusBenchmark.calculateConsensus       100000          10       0  avgt    3   714.159 ±  50.984  ms/op
            ConsensusBenchmark.calculateConsensus       100000          30       0  avgt    3  2358.441 ± 311.102  ms/op
        */
    }
}

