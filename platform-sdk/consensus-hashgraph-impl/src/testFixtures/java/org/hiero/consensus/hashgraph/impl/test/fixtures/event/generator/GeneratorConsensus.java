// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
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

/**
 * Wraps the consensus implementation, orphan buffer, and linker to track consensus state during event generation. Used
 * internally by {@link GeneratorEventGraphSource} to determine correct birth rounds for newly generated events.
 */
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

    /**
     * Creates a new {@code GeneratorConsensus} with the given configuration, time source, and roster.
     *
     * @param configuration the platform configuration
     * @param time          the time source
     * @param roster        the roster of network nodes
     */
    public GeneratorConsensus(
            @NonNull final Configuration configuration, @NonNull final Time time, @NonNull final Roster roster) {
        consensus = new ConsensusImpl(configuration, time, new NoOpConsensusMetrics(), roster);
        linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        orphanBuffer = new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
    }

    /**
     * Feeds an event into the internal consensus, updating the consensus state. This method will modify the given event
     * with metadata such as nGen, and consensus information.
     *
     * @param e the platform event to process
     */
    public void updateConsensus(@NonNull final PlatformEvent e) {
        final List<PlatformEvent> events = orphanBuffer.handleEvent(e);
        if (events.size() != 1) {
            throw new IllegalStateException("Expected exactly one event to be returned from the orphan buffer");
        }
        final EventImpl linkedEvent = linker.linkEvent(events.getFirst());
        if (linkedEvent == null) {
            return;
        }
        final List<ConsensusRound> consensusRounds = consensus.addEvent(linkedEvent);
        if (consensusRounds.isEmpty()) {
            return;
        }
        // if we reach consensus, save the snapshot for future use
        linker.setEventWindow(consensusRounds.getLast().getEventWindow());
    }

    /**
     * Returns the birth round that should be assigned to the next event, which is one greater than the last decided
     * round.
     *
     * @return the current birth round
     */
    public long getCurrentBirthRound() {
        return consensus.getLastRoundDecided() + 1;
    }
}
