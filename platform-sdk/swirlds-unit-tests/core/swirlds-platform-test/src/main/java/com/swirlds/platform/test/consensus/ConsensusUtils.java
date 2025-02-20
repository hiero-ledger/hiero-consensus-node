// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** A class containing utilities for consensus tests. */
public abstract class ConsensusUtils {

    public static void loadEventsIntoGenerator(
            @NonNull final List<PlatformEvent> events,
            @NonNull final GraphGenerator generator,
            @NonNull final Random random) {
        Instant lastTimestamp = Instant.MIN;
        for (final Address address : generator.getAddressBook()) {
            final EventSource source = generator.getSource(address.getNodeId());
            final List<PlatformEvent> eventsByCreator = events.stream()
                    .filter(e -> e.getCreatorId().id() == address.getNodeId().id())
                    .toList();
            eventsByCreator.forEach(e -> {
                final EventImpl eventImpl = new EventImpl(e, null, null);
                source.setLatestEvent(random, eventImpl);
            });
            final Instant creatorMax = eventsByCreator.stream()
                    .max(Comparator.comparingLong(PlatformEvent::getGeneration))
                    .map(e -> e.getTimeCreated())
                    .orElse(Instant.MIN);
            lastTimestamp = Collections.max(Arrays.asList(lastTimestamp, creatorMax));
        }
        generator.setPreviousTimestamp(lastTimestamp);
    }
}
