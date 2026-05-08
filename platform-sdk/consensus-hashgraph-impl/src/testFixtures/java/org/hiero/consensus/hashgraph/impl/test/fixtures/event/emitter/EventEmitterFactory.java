// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.StandardGraphGenerator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.BranchingEventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSourceFactory;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.StandardEventSource;

/**
 * A factory for various {@link EventEmitter} classes.
 */
public class EventEmitterFactory {

    /** the random number generator to use */
    private final Random random;
    /** the roster to use */
    private final Roster roster;
    /**
     * Seed used for the standard generator. Must be same for all instances to ensure the same events are generated
     * for different instances. Differences in the graphs are managed in other ways and are defined in each test.
     */
    private final long commonSeed;

    private final Configuration configuration;

    private final Metrics metrics;

    private final Time time;

    private final EventSourceFactory sourceFactory;

    /**
     * Create a new factory.
     *
     * @param configuration   the configuration to use
     * @param metrics         the metrics to use
     * @param time            the time to use
     * @param random          the random number generator to use
     * @param roster          the roster to use
     */
    public EventEmitterFactory(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Random random,
            @NonNull final Roster roster) {
        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.random = requireNonNull(random);
        this.roster = requireNonNull(roster);
        this.commonSeed = random.nextLong();
        this.sourceFactory = new EventSourceFactory(roster.rosterEntries().size());
    }

    /**
     * Creates a new {@link ShuffledEventEmitter} with a {@link StandardGraphGenerator} using
     * {@link StandardEventSource} that uses real hashes.
     *
     * @return the new {@link EventEmitter}
     */
    public ShuffledEventEmitter newShuffledEmitter() {
        return newShuffledFromSourceFactory();
    }

    public StandardEventEmitter newStandardEmitter() {
        return newStandardFromSourceFactory();
    }

    /**
     * Creates a new {@link ShuffledEventEmitter} with a {@link StandardGraphGenerator} using {@link BranchingEventSource}
     * that uses real hashes.
     *
     * @return the new {@link ShuffledEventEmitter}
     */
    public ShuffledEventEmitter newBranchingShuffledGenerator() {
        final int numNetworkNodes = roster.rosterEntries().size();
        // No more than 1/3 of the nodes can create branches for consensus to be successful
        final int maxNumBranchingSources = (int) Math.floor(numNetworkNodes / 3.0);

        sourceFactory.addCustomSource(
                index -> index < maxNumBranchingSources, EventSourceFactory::newBranchingEventSource);

        return newShuffledFromSourceFactory();
    }

    public ShuffledEventEmitter newShuffledFromSourceFactory() {
        return newShuffledEmitter(sourceFactory.generateSources());
    }

    public StandardEventEmitter newStandardFromSourceFactory() {
        return new StandardEventEmitter(newStandardGraphGenerator(sourceFactory.generateSources()));
    }

    private StandardGraphGenerator newStandardGraphGenerator(final List<EventSource> eventSources) {
        return new StandardGraphGenerator(
                configuration,
                metrics,
                time,
                commonSeed, // standard seed must be the same across all generators
                eventSources,
                roster);
    }

    private ShuffledEventEmitter newShuffledEmitter(final List<EventSource> eventSources) {
        return new ShuffledEventEmitter(
                new StandardGraphGenerator(
                        configuration,
                        metrics,
                        time,
                        commonSeed, // standard seed must be the same across all generators
                        eventSources),
                random.nextLong() // shuffle seed changes every time
                );
    }

    public EventSourceFactory getSourceFactory() {
        return sourceFactory;
    }
}
