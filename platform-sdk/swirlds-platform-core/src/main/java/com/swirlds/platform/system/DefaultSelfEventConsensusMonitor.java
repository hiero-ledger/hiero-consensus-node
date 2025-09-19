// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.swirlds.base.time.Time;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Comparator;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

public class DefaultSelfEventConsensusMonitor implements SelfEventConsensusMonitor {
    private final Time time;
    private final NodeId selfId;
    /**
     * The last consensus time a self event was created.
     */
    private Instant lastSelfEventTime = null;

    public DefaultSelfEventConsensusMonitor(final Time time, final NodeId selfId) {
        this.time = time;
        this.selfId = selfId;
    }

    @Override
    public PlatformStatusAction selfEventInConsensusRound(@NonNull final ConsensusRound round) {
        if (round.isEmpty()) {
            return null;
        }
        final ConsensusEvent lastEventsInRound = round.getConsensusEvents().stream()
                .filter(platformEvent -> platformEvent.getCreatorId().equals(selfId))
                .filter(platformEvent -> platformEvent.getConsensusTimestamp() != null)
                .filter(platformEvent ->
                        lastSelfEventTime == null || lastSelfEventTime.isBefore(platformEvent.getConsensusTimestamp()))
                .max(Comparator.comparing(PlatformEvent::getConsensusTimestamp))
                .orElse(null);

        if (lastEventsInRound != null) {
            lastSelfEventTime = lastEventsInRound.getConsensusTimestamp();
            return new SelfEventReachedConsensusAction(time.now());
        }
        return null;
    }
}
