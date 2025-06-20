// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Applies transactions from rounds that have reached consensus to the state
 */
public interface TransactionHandler {

    /**
     * This method is called after a restart or a reconnect. It provides the previous round's legacy running event hash,
     * in case we need it.
     *
     * @param runningHashUpdate the update to the running hash
     */
    @InputWireLabel("hash override")
    void updateLegacyRunningEventHash(@NonNull RunningEventHashOverride runningHashUpdate);

    /**
     * Applies the transactions in the consensus round to the state
     *
     * @param consensusRound the consensus round to apply
     * @return a new signed state, along with the consensus round that caused it to be created. null if no new state was
     * created
     */
    @Nullable
    TransactionHandlerResult handleConsensusRound(@NonNull ConsensusRound consensusRound);
}
