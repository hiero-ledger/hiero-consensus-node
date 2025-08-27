// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import static com.swirlds.common.test.fixtures.WeightGenerators.BALANCED;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterGenerator;
import com.swirlds.platform.test.fixtures.event.emitter.ShuffledEventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.base.utility.test.fixtures.ResettableRandom;

/** A builder for {@link ConsensusTestOrchestrator} instances */
public class OrchestratorBuilder {
    private int numberOfNodes = 4;
    private WeightGenerator weightGenerator = BALANCED;
    private long seed = 0;
    private int totalEventNum = 10_000;
    private Function<List<Long>, List<EventSource>> eventSourceBuilder = null;
    private Consumer<EventSource> eventSourceConfigurator = es -> {};
    private PlatformContext platformContext;
    /**
     * A function that creates an event emitter based on a graph generator and a seed. They should produce emitters that
     * will emit events in different orders. For example, nothing would be tested if both returned a
     * {@link StandardEventEmitter}. It is for both to return
     * {@link ShuffledEventEmitter} because they will be seeded with different values and therefore emit events in
     * different orders. Each instance of consensus should receive the same events, but in a different order.
     */
    private EventEmitterGenerator node1EventEmitterGenerator = ShuffledEventEmitter::new;

    private EventEmitterGenerator node2EventEmitterGenerator = ShuffledEventEmitter::new;

    public static @NonNull OrchestratorBuilder builder() {
        return new OrchestratorBuilder();
    }

    public @NonNull OrchestratorBuilder setEventSourceBuilder(
            @NonNull final Function<List<Long>, List<EventSource>> eventSourceBuilder) {
        this.eventSourceBuilder = eventSourceBuilder;
        return this;
    }

    public @NonNull OrchestratorBuilder setTestInput(@NonNull final TestInput testInput) {
        numberOfNodes = testInput.numberOfNodes();
        weightGenerator = testInput.weightGenerator();
        seed = testInput.seed();
        totalEventNum = testInput.eventsToGenerate();
        platformContext = testInput.platformContext();
        return this;
    }

    public @NonNull OrchestratorBuilder setEventSourceConfigurator(
            @NonNull final Consumer<EventSource> eventSourceConfigurator) {
        this.eventSourceConfigurator = eventSourceConfigurator;
        return this;
    }

    public @NonNull OrchestratorBuilder setNode1EventEmitterGenerator(
            @NonNull final EventEmitterGenerator node1EventEmitterGenerator) {
        this.node1EventEmitterGenerator = node1EventEmitterGenerator;
        return this;
    }

    public @NonNull OrchestratorBuilder setNode2EventEmitterGenerator(
            @NonNull final EventEmitterGenerator node2EventEmitterGenerator) {
        this.node2EventEmitterGenerator = node2EventEmitterGenerator;
        return this;
    }

    public @NonNull ConsensusTestOrchestrator build() {
        final ResettableRandom random = RandomUtils.initRandom(seed, false);
        final long graphSeed = random.nextLong();
        final long shuffler1Seed = random.nextLong();
        final long shuffler2Seed = random.nextLong();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(numberOfNodes)
                .withWeightGenerator(weightGenerator)
                .build();

        final List<Long> weights =
                roster.rosterEntries().stream().map(RosterEntry::weight).toList();
        final List<EventSource> eventSources;
        if (eventSourceBuilder != null) {
            eventSources = eventSourceBuilder.apply(weights);
        } else {
            eventSources = Stream.generate(() -> new StandardEventSource(false))
                    .map(ses -> (EventSource) ses)
                    .limit(numberOfNodes)
                    .toList();
        }

        for (final EventSource eventSource : eventSources) {
            eventSourceConfigurator.accept(eventSource);
        }
        final StandardGraphGenerator graphGenerator =
                new StandardGraphGenerator(platformContext, graphSeed, eventSources, roster);

        // Make the graph generators create a fresh set of events.
        // Use the same seed so that they create identical graphs.
        final EventEmitter node1Emitter =
                node1EventEmitterGenerator.getEventEmitter(graphGenerator.cleanCopy(), shuffler1Seed);
        final EventEmitter node2Emitter =
                node2EventEmitterGenerator.getEventEmitter(graphGenerator.cleanCopy(), shuffler2Seed);

        final List<ConsensusTestNode> nodes = new ArrayList<>();
        // Create two instances to run consensus on. Each instance reseeds the emitter so that they
        // emit events in different orders.
        nodes.add(ConsensusTestNode.genesisContext(platformContext, node1Emitter));
        nodes.add(ConsensusTestNode.genesisContext(platformContext, node2Emitter));

        return new ConsensusTestOrchestrator(platformContext, nodes, weights, totalEventNum);
    }
}
