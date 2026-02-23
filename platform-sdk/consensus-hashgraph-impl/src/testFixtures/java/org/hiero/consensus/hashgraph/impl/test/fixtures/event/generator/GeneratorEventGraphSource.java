// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;

import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.RandomEventUtils.DEFAULT_FIRST_EVENT_TIME_CREATED;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.event.EventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.signing.GeneratorEventSigner;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.Randotron;

/**
 * A utility class for generating a graph of events.
 */
public class GeneratorEventGraphSource implements EventGraphSource {

    private final EventDescriptor[] latestEventPerNode;

    /**
     * The roster.
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

    private final GeneratorEventSigner eventSigner;
    private final boolean populateNgen;

    /**
     * Creates a new graph generator.
     *
     * @param configuration   the platform configuration
     * @param time            the time source
     * @param seed            the random seed for deterministic event generation
     * @param maxOtherParents the maximum number of other-parents an event can have
     * @param roster          the roster of network nodes
     * @param eventSigner     the signer used to produce event signatures
     * @param populateNgen    whether to populate ngen values on generated events
     */
    GeneratorEventGraphSource(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            final long seed,
            final int maxOtherParents,
            @NonNull final Roster roster,
            @NonNull final GeneratorEventSigner eventSigner,
            final boolean populateNgen) {
        this.maxOtherParents = maxOtherParents;
        this.random = Randotron.create(seed);
        this.latestEventPerNode = new EventDescriptor[roster.rosterEntries().size()];
        this.roster = roster;
        this.consensus = new GeneratorConsensus(configuration, time, roster);
        this.hasher = new PbjStreamHasher();
        this.eventSigner = eventSigner;
        this.populateNgen = populateNgen;
    }

    /**
     * Returns the roster used by this generator.
     *
     * @return the roster
     */
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

    /**
     * Generates a single event with the hash populated.
     *
     * @return the generated event
     */
    @NonNull
    @Override
    public PlatformEvent next() {
        final List<Integer> nodeIndices = IntStream.range(
                        0, roster.rosterEntries().size())
                .boxed()
                .collect(ArrayList::new, List::add, List::addAll);
        Collections.shuffle(nodeIndices, random);

        final Integer eventCreator = nodeIndices.removeLast();
        final List<EventDescriptor> parents = new ArrayList<>();
        if (latestEventPerNode[eventCreator] != null) {
            parents.add(latestEventPerNode[eventCreator]);
        }
        nodeIndices.subList(0, Math.min(maxOtherParents, nodeIndices.size())).stream()
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
                coin);
        hasher.hashUnsignedEvent(unsignedEvent);

        final PlatformEvent platformEvent = new PlatformEvent(unsignedEvent, eventSigner.signEvent(unsignedEvent),
                EventOrigin.GOSSIP);
        consensus.updateConsensus(platformEvent);

        // The event given to consensus will be modified by it with consensus information as some point. We do not want
        // to modify the events that are returned to the caller, so the caller gets a separate copy of the event.
        final PlatformEvent copy = platformEvent.copyGossipedData();
        copy.signalPrehandleCompletion();

        latestEventPerNode[eventCreator] = copy.getDescriptor().eventDescriptor();
        if (populateNgen) {
            // the event sent to consensus will have its nGen value populated, we should copy this value if the caller
            // wants ngen values to be populated on the returned events
            copy.setNGen(platformEvent.getNGen());
        }
        return copy;
    }

    @Override
    public boolean hasNext() {
        return true;
    }
}
