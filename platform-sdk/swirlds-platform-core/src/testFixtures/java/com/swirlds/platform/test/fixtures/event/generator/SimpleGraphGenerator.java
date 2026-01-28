// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import static com.swirlds.platform.test.fixtures.event.RandomEventUtils.DEFAULT_FIRST_EVENT_TIME_CREATED;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import org.hiero.base.crypto.SignatureType;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A utility class for generating a graph of events.
 */
public class SimpleGraphGenerator {

    private final EventDescriptor[] latestEventPerNode;

    /** The maximum number of other-parents that newly generated events should have */
    private final int maxOtherParents;

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
    private final Random random;

    private final GeneratorConsensus consensus;

    /**
     * Construct a new StandardEventGenerator.
     * <p>
     * Note: once an event source has been passed to this constructor it should not be modified by the outer context.
     *
     * @param seed The random seed used to generate events.
     * @param maxOtherParents The maximum number of other-parents for each event.
     * @param roster The roster to use.
     */
    public SimpleGraphGenerator(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            final long seed,
            final int maxOtherParents,
            @NonNull final Roster roster) {
        this.maxOtherParents = maxOtherParents;
        this.random = new Random(seed);
        this.latestEventPerNode = new EventDescriptor[roster.rosterEntries().size()];
        this.roster = roster;
        consensus = new GeneratorConsensus(configuration, time, roster);

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

        latestEventTime = latestEventTime.minusMillis(random.nextInt(1, 5));
        return latestEventTime;
    }

    /**
     * Build the event that will be returned by getNextEvent.
     */
    public GossipEvent buildNextEvent() {
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

        final byte[] sig = new byte[SignatureType.RSA.signatureLength()];
        random.nextBytes(sig);

        final long birthRound = consensus.getCurrentBirthRound();
        final EventCore eventCore = EventCore.newBuilder()
                .creatorNodeId(roster.rosterEntries().get(eventCreator).nodeId())
                .birthRound(birthRound)
                .timeCreated(HapiUtils.asTimestamp(getNextTimestamp()))
                .coin(random.nextInt(0, roster.rosterEntries().size()+1))
                .build();
        final GossipEvent gossipEvent = GossipEvent.newBuilder()
                .eventCore(eventCore)
                .parents(parents)
                .signature(Bytes.wrap(sig))
                //TODO transactions
                .build();

        final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
        new DefaultEventHasher().hashEvent(platformEvent);
        consensus.updateConsensus(platformEvent);
        latestEventPerNode[eventCreator] = platformEvent.getDescriptor().eventDescriptor();
        return gossipEvent;
    }
}
