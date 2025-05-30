// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId selfId;
    private final List<ConsensusRound> consensusRounds = new ArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param selfId the node ID of the node
     */
    public NodeResultsCollector(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);
    }

    /**
     * Adds a consensus round to the list of rounds created during the test.
     *
     * @param rounds the consensus rounds to add
     */
    public void addConsensusRounds(@NonNull final List<ConsensusRound> rounds) {
        requireNonNull(rounds);
        consensusRounds.addAll(rounds);
    }

    /**
     * Adds a {@link PlatformStatus} to the list of collected statuses.
     *
     * @param status the {@link PlatformStatus} to add
     */
    public void addPlatformStatus(@NonNull final PlatformStatus status) {
        requireNonNull(status);
        platformStatuses.add(status);
    }

    /**
     * Returns a {@link SingleNodeConsensusResult} of the current state.
     *
     * @return the {@link SingleNodeConsensusResult}
     */
    @NonNull
    public SingleNodeConsensusResult getConsensusResult() {
        return new SingleNodeConsensusResultImpl(selfId, new ArrayList<>(consensusRounds));
    }

    /**
     * Returns a {@link SingleNodeStatusProgression} of the current state.
     *
     * @return the {@link SingleNodeStatusProgression}
     */
    public SingleNodeStatusProgression getStatusProgression() {
        return new SingleNodeStatusProgressionImpl(selfId, new ArrayList<>(platformStatuses));
    }
}
