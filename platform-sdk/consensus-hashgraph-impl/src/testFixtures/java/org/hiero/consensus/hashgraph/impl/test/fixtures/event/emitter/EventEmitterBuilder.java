// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.StandardGraphGenerator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSourceFactory;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;

/**
 * Builder class for creating instances of {@link EventEmitter}.
 */
public class EventEmitterBuilder {
    private long randomSeed = 0;
    private int numNodes = 4;
    private int maxOtherParents = 1;
    private WeightGenerator weightGenerator = WeightGenerators.GAUSSIAN;
    private Configuration configuration;
    private Metrics metrics;
    private Time time;

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
     * Sets the maximum number of other-parents for the event emitter.
     *
     * @param maxOtherParents the maximum number of other-parents
     * @return the builder instance
     */
    public EventEmitterBuilder setMaxOtherParents(final int maxOtherParents) {
        this.maxOtherParents = maxOtherParents;
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
     * Sets the configuration for the event emitter.
     *
     * @param configuration the configuration
     * @return the builder instance
     */
    public EventEmitterBuilder setConfiguration(final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
        return this;
    }

    /**
     * Sets the metrics for the event emitter.
     *
     * @param metrics the metrics
     * @return the builder instance
     */
    public EventEmitterBuilder setMetrics(final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
        return this;
    }

    /**
     * Sets the time for the event emitter.
     *
     * @param time the time
     * @return the builder instance
     */
    public EventEmitterBuilder setTime(final Time time) {
        this.time = requireNonNull(time);
        return this;
    }

    /**
     * Builds and returns an instance of {@link EventEmitter}.
     *
     * @return the event emitter instance
     */
    public StandardEventEmitter build() {
        final Randotron random = Randotron.create(randomSeed);
        if (configuration == null) {
            configuration = new TestConfigBuilder().getOrCreateConfig();
        }
        if (metrics == null) {
            metrics = new NoOpMetrics();
        }
        if (time == null) {
            time = Time.getCurrent();
        }

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(weightGenerator)
                .withSize(numNodes)
                .build();

        final EventSourceFactory eventSourceFactory = new EventSourceFactory(numNodes);

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                configuration,
                metrics,
                time,
                randomSeed,
                maxOtherParents,
                eventSourceFactory.generateSources(),
                roster);
        return new StandardEventEmitter(generator);
    }
}
