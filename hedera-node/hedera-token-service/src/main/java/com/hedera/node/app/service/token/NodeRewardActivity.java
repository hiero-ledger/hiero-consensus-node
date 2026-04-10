// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the reward activity of a specific node over a staking period.
 * This record holds activity-related data for a node, including its identifier,
 * associated account, missed rounds, total rounds in the period, and the
 * minimum percentage of rounds required to qualify as active.
 */
public record NodeRewardActivity(
        long nodeId,
        @NonNull AccountID accountId,
        long numMissedRounds,
        long roundsInPeriod,
        int minJudgeRoundPercentage) {

    public NodeRewardActivity {
        requireNonNull(accountId);
    }

    /**
     * Calculates the percentage of rounds in which the node was active.
     *
     * @return the activity percentage
     */
    public double activePercent() {
        final var activeRounds = Math.max(roundsInPeriod - numMissedRounds, 0);
        return roundsInPeriod == 0 ? 0 : ((double) (activeRounds * 100)) / roundsInPeriod;
    }
}
