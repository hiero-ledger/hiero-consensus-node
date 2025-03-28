// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.NodeRewards;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with node rewards.
 */
public interface ReadableNodeRewardsStore {
    /**
     * Returns the {link NodeRewards} in state.
     *
     * @return the {link NodeRewards} in state
     */
    NodeRewards get();

    /**
     * Calculates all the active nodes based on the active percent. A node is considered active if it has missed
     * creating judges in less than the active percent of the total number of rounds in the staking period.
     *
     * @param rosterEntries the list of roster entries
     * @param minJudgeRoundPercentage the minimum percentage of rounds an "active" node would have created judges in
     * @return the list of active node ids
     */
    List<Long> getActiveNodeIds(@NonNull List<RosterEntry> rosterEntries, int minJudgeRoundPercentage);
}
