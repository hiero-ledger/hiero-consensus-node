// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSourceFactory;

/**
 * Builder class for creating instances of {@link EventEmitter}.
 */
public class EventEmitterBuilder {
    private long randomSeed = 0;
    private int numNodes = 4;
    private WeightGenerator weightGenerator = WeightGenerators.GAUSSIAN;
    private PlatformContext platformContext = null;

    private EventEmitterBuilder() {}

    public static EventEmitterBuilder newBuilder() {
        return new EventEmitterBuilder();
    }

    /**
     * Sets the random seed for the event emitter.
     *
     * @param randomSeed the random seed
     * @return the builder instance
     */
    public EventEmitterBuilder setRandomSeed(final long randomSeed) {
        this.randomSeed = randomSeed;
        return this;
    }

    /**
     * Sets the number of nodes for the event emitter.
     *
     * @param numNodes the number of nodes
     * @return the builder instance
     */
    public EventEmitterBuilder setNumNodes(final int numNodes) {
        this.numNodes = numNodes;
        return this;
    }

    /**
     * Sets the weight generator for the event emitter.
     *
     * @param weightGenerator the weight generator
     * @return the builder instance
     */
    public EventEmitterBuilder setWeightGenerator(final WeightGenerator weightGenerator) {
        this.weightGenerator = weightGenerator;
        return this;
    }

    /**
     * Sets the platform context for the event emitter.
     *
     * @param platformContext the platform context
     * @return the builder instance
     */
    public EventEmitterBuilder setPlatformContext(final PlatformContext platformContext) {
        this.platformContext = platformContext;
        return this;
    }

    /**
     * Builds and returns an instance of {@link EventEmitter}.
     *
     * @return the event emitter instance
     */
    public StandardEventEmitter build() {
        final Randotron random = Randotron.create(randomSeed);
        if (platformContext == null) {
            platformContext = TestPlatformContextBuilder.create().build();
        }

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(weightGenerator)
                .withSize(numNodes)
                .build();

        final EventSourceFactory eventSourceFactory = new EventSourceFactory(numNodes);

        final StandardGraphGenerator generator =
                new StandardGraphGenerator(platformContext, randomSeed, eventSourceFactory.generateSources(), roster);
        return new StandardEventEmitter(generator);
    }
}
