/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.test.event.emitter;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.event.source.EventSourceFactory;
import com.swirlds.platform.test.event.source.ForkingEventSource;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
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
    /** the address book to use */
    private final AddressBook addressBook;
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
     * @param addressBook     the address book to use
     */
    public EventEmitterFactory(
            @NonNull final PlatformContext platformContext,
            @NonNull final Random random,
            @NonNull final AddressBook addressBook) {
        this.random = Objects.requireNonNull(random);
        this.addressBook = Objects.requireNonNull(addressBook);
        this.commonSeed = random.nextLong();
        this.sourceFactory = new EventSourceFactory(addressBook);
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
        final int numNetworkNodes = addressBook.getSize();
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
        if (addressBook == null) {
            return new StandardGraphGenerator(
                    platformContext,
                    commonSeed, // standard seed must be the same across all generators
                    eventSources);
        } else {
            return new StandardGraphGenerator(
                    platformContext,
                    commonSeed, // standard seed must be the same across all generators
                    eventSources,
                    addressBook);
        }
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
