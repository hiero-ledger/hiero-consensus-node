/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.core.jmh;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.event.source.EventSourceFactory;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
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
        final List<EventSource> eventSources =
                EventSourceFactory.newStandardEventSources(WeightGenerators.balancedNodeWeights(numNodes));

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StandardGraphGenerator generator = new StandardGraphGenerator(platformContext, seed, eventSources);
        final StandardEventEmitter emitter = new StandardEventEmitter(generator);
        events = emitter.emitEvents(numEvents);
        final AddressBook addressBook = emitter.getGraphGenerator().getAddressBook();

        consensus = new ConsensusImpl(
                platformContext, new NoOpConsensusMetrics(), RosterRetriever.buildRoster(addressBook));
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
