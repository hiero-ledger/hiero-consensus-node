// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a grouping of node rewards categorized into active and inactive nodes based on their
 * participation and activity in a staking period.
 * <p>
 * The grouping is determined by evaluating node activity data along with the minimum judge round
 * percentage criteria. Each group consists of nodes and their associated information.
 * <p>
 * The class provides utility methods for retrieving distinct sets of active and inactive node IDs
 * and account IDs, as well as merging all node activities into a single set.
 * <p>
 * Immutable collections are used to ensure that the grouping remains unchanged once created.
 */
public record NodeRewardGroups(
        @NonNull Set<NodeRewardActivity> activeNodeActivities,
        @NonNull Set<NodeRewardActivity> inactiveNodeActivities) {

    /**
     * Represents a node that has been determined to be reward-eligible and is a candidate
     * for inclusion in a {@code NodeRewardGroups}. The caller is responsible for filtering
     * out ineligible nodes (e.g. those declining rewards) before constructing candidates.
     */
    public record NodeRewardCandidate(long nodeId, @NonNull AccountID accountId) {
        public NodeRewardCandidate {
            requireNonNull(accountId);
        }
    }

    /**
     * Represents the reward activity of a specific node over a period of time.
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
         * @return the activity percentage.
         */
        public double activePercent() {
            final var activeRounds = Math.max(roundsInPeriod - numMissedRounds, 0);
            return roundsInPeriod == 0 ? 0 : ((double) (activeRounds * 100)) / roundsInPeriod;
        }

        /**
         * Determines if the node is considered active based on the missed rounds count
         * and the required judge round percentage.
         *
         * @return true if the node is active, false otherwise.
         */
        public boolean isActive() {
            return numMissedRounds <= this.calcMaxMissedJudgesAmount();
        }

        // Calculate the maximum number of missed judges allowed for a node to be considered active.
        private long calcMaxMissedJudgesAmount() {
            return BigInteger.valueOf(this.roundsInPeriod)
                    .multiply(BigInteger.valueOf(100 - minJudgeRoundPercentage))
                    .divide(BigInteger.valueOf(100))
                    .longValueExact();
        }
    }


    /**
     * Creates a new instance of {@code NodeRewardGroups} from the given pre-filtered candidate
     * list, node rewards, and minimum judge round percentage.
     *
     * @param candidates the list of reward-eligible node candidates (already filtered for
     *                   eligibility by the caller).
     * @param nodeRewards the rewards data containing information about node activities.
     * @param minJudgeRoundPercentage the minimum percentage of judge rounds required for a node
     *                                to be considered active.
     * @return a {@code NodeRewardGroups} instance categorizing nodes into active and inactive
     *         groups based on the provided rules.
     */
    public static NodeRewardGroups from(
            @NonNull final List<NodeRewardCandidate> candidates,
            @NonNull final NodeRewards nodeRewards,
            final int minJudgeRoundPercentage) {
        requireNonNull(candidates);
        requireNonNull(nodeRewards);

        final long roundsLastPeriod = nodeRewards.numRoundsInStakingPeriod();
        final var missedJudgeCounts = nodeRewards.nodeActivities().stream()
            .collect(toMap(NodeActivity::nodeId, NodeActivity::numMissedJudgeRounds));

        final var active = new HashSet<NodeRewardActivity>();
        final var inactive = new HashSet<NodeRewardActivity>();

        for (final var candidate : candidates) {
            final long missedJudges = missedJudgeCounts.getOrDefault(candidate.nodeId(), 0L);
            final var activity = new NodeRewardActivity(
                candidate.nodeId(), candidate.accountId(), missedJudges, roundsLastPeriod, minJudgeRoundPercentage);

            if (activity.isActive()) {
                active.add(activity);
            } else {
                inactive.add(activity);
            }
        }
        return new NodeRewardGroups(active, inactive);
    }

    /**
     * Creates a new instance of {@code NodeRewardGroups} and ensures the activity sets are
     * unmodifiable.
     *
     * @param activeNodeActivities the set of active node activities.
     * @param inactiveNodeActivities the set of inactive node activities.
     */
    public NodeRewardGroups {
        activeNodeActivities = Collections.unmodifiableSet(activeNodeActivities);
        inactiveNodeActivities = Collections.unmodifiableSet(inactiveNodeActivities);
    }

    /**
     * Returns a merged set containing all node activities (both active and inactive).
     *
     * @return a set of all node activities.
     */
    public Set<NodeRewardActivity> allNodeActivities() {
        Set<NodeRewardActivity> allActivities = new HashSet<>();
        allActivities.addAll(activeNodeActivities);
        allActivities.addAll(inactiveNodeActivities);
        return Collections.unmodifiableSet(allActivities);
    }

    /**
     * Returns a set containing all node IDs from both active and inactive groups.
     *
     * @return a set of all node IDs.
     */
    public Set<Long> allNodeIds() {
        return allNodeActivities().stream()
                .map(NodeRewardActivity::nodeId)
                .collect(toCollection(HashSet::new));
    }

    /**
     * Returns the set of active node IDs.
     *
     * @return the set of active node IDs.
     */
    public Set<Long> activeNodeIds() {
        return activeNodeActivities.stream()
                .map(NodeRewardActivity::nodeId)
                .collect(toCollection(HashSet::new));
    }

    /**
     * Returns the set of inactive node IDs.
     *
     * @return the set of inactive node IDs.
     */
    public Set<Long> inactiveNodeIds() {
        return inactiveNodeActivities.stream()
                .map(NodeRewardActivity::nodeId)
                .collect(toCollection(HashSet::new));
    }

    /**
     * Returns the set of active node account IDs.
     *
     * @return the set of active node account IDs.
     */
    public Set<AccountID> activeNodeAccountIds() {
        return activeNodeActivities.stream()
                .map(NodeRewardActivity::accountId)
                .collect(toCollection(HashSet::new));
    }

    /**
     * Returns the set of inactive node account IDs.
     *
     * @return the set of inactive node account IDs.
     */
    public Set<AccountID> inactiveNodeAccountIds() {
        return inactiveNodeActivities.stream()
                .map(NodeRewardActivity::accountId)
                .collect(toCollection(HashSet::new));
    }

}
