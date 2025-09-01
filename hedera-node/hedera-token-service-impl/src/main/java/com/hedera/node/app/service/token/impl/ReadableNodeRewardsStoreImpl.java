// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableNodeRewardsStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;

/**
 * Default implementation of {@link ReadableNetworkStakingRewardsStore}.
 */
public class ReadableNodeRewardsStoreImpl implements ReadableNodeRewardsStore {

    /**
     * The underlying data storage class that holds staking reward data for all nodes.
     */
    private final ReadableSingletonState<NodeRewards> nodeRewardsState;

    /**
     * Create a new {@link ReadableNodeRewardsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNodeRewardsStoreImpl(@NonNull final ReadableStates states) {
        this.nodeRewardsState = requireNonNull(states).getSingleton(NODE_REWARDS_STATE_ID);
    }

    @Override
    public NodeRewards get() {
        return requireNonNull(nodeRewardsState.get());
    }

    /**
     * Returns the list of active node ids based on the minimum percentage of rounds an "active" node would have
     * created judges in.
     * @param rosterEntries The list of roster entries.
     * @param minJudgeRoundPercentage The minimum percentage of rounds an "active" node would have created judges in
     * @return The list of active node ids.
     */
    public List<Long> getActiveNodeIds(
            @NonNull final List<RosterEntry> rosterEntries, final int minJudgeRoundPercentage) {
        requireNonNull(rosterEntries);
        final long roundsLastPeriod = requireNonNull(nodeRewardsState.get()).numRoundsInStakingPeriod();
        final long maxMissedJudges = BigInteger.valueOf(roundsLastPeriod)
                .multiply(BigInteger.valueOf(100 - minJudgeRoundPercentage))
                .divide(BigInteger.valueOf(100))
                .longValueExact();
        final var missedJudgeCounts = get().nodeActivities().stream()
                .collect(toMap(NodeActivity::nodeId, NodeActivity::numMissedJudgeRounds));
        return rosterEntries.stream()
                .map(RosterEntry::nodeId)
                .filter(nodeId -> missedJudgeCounts.getOrDefault(nodeId, 0L) <= maxMissedJudges)
                .toList();
    }
}
