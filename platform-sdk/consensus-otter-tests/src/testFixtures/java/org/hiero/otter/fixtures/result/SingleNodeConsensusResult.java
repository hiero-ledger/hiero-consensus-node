// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface that provides access to the consensus results of a single node that are created during a test.
 */
public interface SingleNodeConsensusResult extends OtterResult {

    /**
     * Returns the node ID of the node that created the results.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the number of the last round created so far.
     *
     * @return the last round number or {@code -1} if no rounds were created
     */
    default long lastRoundNum() {
        return consensusRounds().stream()
                .mapToLong(ConsensusRound::getRoundNum)
                .max()
                .orElse(-1L);
    }

    /**
     * Returns the list of consensus rounds created during the test up to this moment. The list is ordered such that
     * earlier rounds have lower indices.
     *
     * @return the list of consensus rounds
     */
    @NonNull
    List<ConsensusRound> consensusRounds();

    /**
     * Returns the event window of the latest consensus round, the genesis event window if no rounds have reached
     * consensus.
     *
     * @return the latest event window
     */
    @NonNull
    EventWindow getLatestEventWindow();

    /**
     * Returns the events that the node emitted for pre-consensus handling since the last {@link #clear()}, in the order
     * they were emitted.
     *
     * @return the descriptors of the pre-consensus events
     * @throws UnsupportedOperationException if the environment does not collect pre-consensus events
     */
    @NonNull
    List<EventDescriptorWrapper> preConsensusEvents();

    /**
     * Returns the events that the node reported as stale since the last {@link #clear()}, in the order they were
     * reported.
     *
     * @return the descriptors of the stale events
     * @throws UnsupportedOperationException if the environment does not collect stale events
     */
    @NonNull
    List<EventDescriptorWrapper> staleEvents();

    /**
     * Subscribes to {@link ConsensusRound}s created by the node.
     *
     * <p>The subscriber will be notified every time one (or more) rounds reach consensus.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    void subscribe(@NonNull ConsensusRoundSubscriber subscriber);
}
