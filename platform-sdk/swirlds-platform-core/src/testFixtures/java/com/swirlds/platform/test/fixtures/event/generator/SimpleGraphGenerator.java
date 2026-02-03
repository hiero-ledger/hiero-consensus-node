// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import static com.swirlds.platform.test.fixtures.event.RandomEventUtils.DEFAULT_FIRST_EVENT_TIME_CREATED;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.test.fixtures.event.signer.EventSigner;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.Randotron;

/**
 * A utility class for generating a graph of events.
 */
public class SimpleGraphGenerator {

    private final EventDescriptor[] latestEventPerNode;

    /**
     * The roster representing the event sources.
     */
    private final Roster roster;

    /**
     * The timestamp of the previously emitted event.
     */
    private Instant latestEventTime;
    /**
     * The source of all randomness for this class.
     */
    private final Randotron random;

    private final GeneratorConsensus consensus;
    private final PbjStreamHasher hasher;
    /** The maximum number of other parents an event can have */
    private final int maxOtherParents;
    private final EventSigner eventSigner;

    /**
     *
     * @param roster The roster to use.
     */
    public SimpleGraphGenerator(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            final long seed,
            final int maxOtherParents,
            @NonNull final Roster roster,
            @NonNull final EventSigner eventSigner) {
        this.maxOtherParents = maxOtherParents;
        this.random = Randotron.create(seed);
        this.latestEventPerNode = new EventDescriptor[roster.rosterEntries().size()];
        this.roster = roster;
        this.consensus = new GeneratorConsensus(configuration, time, roster);
        this.hasher = new PbjStreamHasher();
        this.eventSigner = eventSigner;
    }


    public @NonNull Roster getRoster() {
        return roster;
    }

    /**
     * Get the next timestamp for the next event.
     */
    private Instant getNextTimestamp() {
        if (latestEventTime == null) {
            latestEventTime = DEFAULT_FIRST_EVENT_TIME_CREATED;
            return latestEventTime;
        }

        latestEventTime = latestEventTime.plusMillis(random.nextInt(1, 5));
        return latestEventTime;
    }

    public List<PlatformEvent> generateEvents(final int count) {
        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(generateEvent());
        }
        return events;
    }

    /**
     * Build the event
     */
    public PlatformEvent generateEvent() {
        final List<Integer> nodeIndices = IntStream.range(0, roster.rosterEntries().size()).boxed()
                .collect(ArrayList::new, List::add, List::addAll);
        Collections.shuffle(nodeIndices, random);


        final Integer eventCreator = nodeIndices.removeLast();
        final List<EventDescriptor> parents = new ArrayList<>();
        if(latestEventPerNode[eventCreator] != null) {
            parents.add(latestEventPerNode[eventCreator]);
        }
        nodeIndices
                .subList(0, Math.min(maxOtherParents, nodeIndices.size()))
                .stream()
                .map(i -> latestEventPerNode[i])
                .filter(Objects::nonNull)
                .forEach(parents::add);

        final long birthRound = consensus.getCurrentBirthRound();
        final List<Bytes> transactions = Stream.generate(() -> random.randomBytes(1, 100))
                .limit(random.nextInt(0, 5))
                .toList();
        final int coin = random.nextInt(0, roster.rosterEntries().size() + 1);
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                NodeId.of(roster.rosterEntries().get(eventCreator).nodeId()),
                parents.stream().map(EventDescriptorWrapper::new).toList(),
                birthRound,
                getNextTimestamp(),
                transactions,
                coin
        );
        hasher.hashUnsignedEvent(unsignedEvent);

        final PlatformEvent platformEvent = new PlatformEvent(unsignedEvent, eventSigner.signEvent(unsignedEvent));
        platformEvent.signalPrehandleCompletion();
        consensus.updateConsensus(platformEvent);
        latestEventPerNode[eventCreator] = platformEvent.getDescriptor().eventDescriptor();
        return platformEvent;
    }
}
