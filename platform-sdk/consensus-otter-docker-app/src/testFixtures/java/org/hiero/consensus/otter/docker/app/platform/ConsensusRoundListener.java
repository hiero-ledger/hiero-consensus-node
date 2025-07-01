// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Functional interface for receiving consensus rounds from the platform.
 */
@FunctionalInterface
public interface ConsensusRoundListener {
    /**
     * Called when a new batch of consensus rounds has been produced.
     *
     * @param rounds the list of consensus rounds
     */
    void onConsensusRounds(@NonNull List<ConsensusRound> rounds);
}
