// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Functional interface for receiving consensus rounds from the platform.
 */
@FunctionalInterface
public interface ConsensusRoundListener {
    /**
     * Called when a new consensus round has been produced.
     *
     * @param round the consensus round
     */
    void onConsensusRound(@NonNull ConsensusRound round);
}
