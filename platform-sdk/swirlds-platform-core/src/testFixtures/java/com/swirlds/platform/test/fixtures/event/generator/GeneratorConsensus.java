package com.swirlds.platform.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;

public class GeneratorConsensus {
    /**
     * The consensus implementation for determining birth rounds of events.
     */
    private final ConsensusImpl consensus;

    /** Used to assign nGen values to events. This value is used by consensus, so it must be set. */
    private final OrphanBuffer orphanBuffer;

    /**
     * The linker for events to use with the internal consensus.
     */
    private final ConsensusLinker linker;

    public GeneratorConsensus(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final Roster roster) {
        consensus = new ConsensusImpl(configuration, time, new NoOpConsensusMetrics(), roster);
        linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        orphanBuffer = new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
    }

    public void updateConsensus(@NonNull final PlatformEvent e) {
        /* The event given to the internal consensus needs its own EventImpl & PlatformEvent for
        metadata to be kept separate from the event that is returned to the caller.  The orphan
        buffer assigns an nGen value. The SimpleLinker wraps the event in an EventImpl and links
        it. The event must be hashed and have a descriptor built for its use in the SimpleLinker. */
        final PlatformEvent copy = e.copyGossipedData();
        final List<PlatformEvent> events = orphanBuffer.handleEvent(copy);
        for (final PlatformEvent event : events) {
            final EventImpl linkedEvent = linker.linkEvent(event);
            if (linkedEvent == null) {
                continue;
            }
            final List<ConsensusRound> consensusRounds = consensus.addEvent(linkedEvent);
            if (consensusRounds.isEmpty()) {
                continue;
            }
            // if we reach consensus, save the snapshot for future use
            linker.setEventWindow(consensusRounds.getLast().getEventWindow());
        }
    }

    public long getCurrentBirthRound(){
        return consensus.getLastRoundDecided() + 1;
    }
}
