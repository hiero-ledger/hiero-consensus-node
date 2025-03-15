// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.EventSourceFactory;
import com.swirlds.platform.test.fixtures.event.source.ForkingEventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * A factory for various {@link EventEmitter} classes.
 */
public class EventEmitterFactory {

    /** the random number generator to use */
    private final Random random;
    /** the roster to use */
    private final Roster roster;
    /**
     * Seed used for the standard generator. Must be same for all instances to ensure the same events are
     * generated for different instances. Differences in the graphs are managed in other ways and are defined in each
     * test.
     */
    private final long commonSeed;
    /** the platform context containing configuration */
    private final PlatformContext platformContext;

    private final EventSourceFactory sourceFactory;

    /**
     * Create a new factory.
     *
     * @param platformContext the platform context
     * @param random          the random number generator to use
     * @param roster          the roster to use
     */
    public EventEmitterFactory(
            @NonNull final PlatformContext platformContext,
            @NonNull final Random random,
            @NonNull final Roster roster) {
        this.random = Objects.requireNonNull(random);
        this.roster = Objects.requireNonNull(roster);
        this.commonSeed = random.nextLong();
        this.sourceFactory = new EventSourceFactory(roster.rosterEntries().size());
        this.platformContext = Objects.requireNonNull(platformContext);
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
     * Creates a new {@link ShuffledEventEmitter} with a {@link StandardGraphGenerator} using {@link ForkingEventSource}
     * that uses real hashes.
     *
     * @return the new {@link ShuffledEventEmitter}
     */
    public ShuffledEventEmitter newForkingShuffledGenerator() {
        final int numNetworkNodes = roster.rosterEntries().size();
        // No more than 1/3 of the nodes can create forks for consensus to be successful
        final int maxNumForkingSources = (int) Math.floor(numNetworkNodes / 3.0);

        sourceFactory.addCustomSource(index -> index < maxNumForkingSources, EventSourceFactory::newForkingEventSource);

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
                platformContext,
                commonSeed, // standard seed must be the same across all generators
                eventSources,
                roster);
    }

    private ShuffledEventEmitter newShuffledEmitter(final List<EventSource> eventSources) {
        return new ShuffledEventEmitter(
                new StandardGraphGenerator(
                        platformContext,
                        commonSeed, // standard seed must be the same across all generators
                        eventSources),
                random.nextLong() // shuffle seed changes every time
                );
    }

    public EventSourceFactory getSourceFactory() {
        return sourceFactory;
    }
}
