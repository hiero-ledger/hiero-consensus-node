// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a grouping of reward-eligible nodes categorized into active and inactive groups
 * based on their participation in a staking period.
 * <p>
 * Nodes that decline rewards must be excluded by the caller before invoking {@link #from}.
 * The resulting active and inactive groups are disjoint and contain only nodes that are
 * eligible for reward distribution.
 * <p>
 * Immutable collections are used to ensure that the grouping remains unchanged once created.
 */
public record NodeRewardGroups(
        @NonNull List<NodeRewardActivity> activeNodeActivities,
        @NonNull List<NodeRewardActivity> inactiveNodeActivities) {

    /**
     * The criteria currently used to determine whether a node is active.
     */
    private static NodeActivityCriteria activityCriteria = NodeActivityCriteria.DEFAULT;

    /**
     * Strategy that decides whether a {@link NodeRewardActivity} represents an active node. The {@link #DEFAULT}
     * implementation checks whether the number of missed rounds falls within the allowed threshold derived from the
     * node's {@code minJudgeRoundPercentage}.
     */
    @FunctionalInterface
    public interface NodeActivityCriteria {

        /**
         * The production criteria: a node is active when its missed rounds do not exceed the allowed threshold.
         */
        NodeActivityCriteria DEFAULT = activity -> {
            final long maxMissed = BigInteger.valueOf(activity.roundsInPeriod())
                    .multiply(BigInteger.valueOf(100 - activity.minJudgeRoundPercentage()))
                    .divide(BigInteger.valueOf(100))
                    .longValueExact();
            return activity.numMissedRounds() <= maxMissed;
        };

        /**
         * Returns {@code true} if the given activity should be classified as active.
         *
         * @param activity the node reward activity to evaluate
         * @return whether the node is considered active
         */
        boolean isActive(@NonNull NodeRewardActivity activity);
    }

    /**
     * Replaces the activity criteria used to determine whether a node is active.
     *
     * @param criteria the new criteria; must not be {@code null}
     */
    public static void setActivityCriteria(@NonNull final NodeActivityCriteria criteria) {
        activityCriteria = requireNonNull(criteria);
    }

    /**
     * Restores the {@link NodeActivityCriteria#DEFAULT default} activity criteria.
     */
    public static void resetActivityCriteria() {
        activityCriteria = NodeActivityCriteria.DEFAULT;
    }

    /**
     * Creates a new instance of {@code NodeRewardGroups} and ensures the activity sets are
     * unmodifiable.
     *
     * @param activeNodeActivities   the list of active node activities.
     * @param inactiveNodeActivities the list of inactive node activities.
     */
    public NodeRewardGroups {
        activeNodeActivities = Collections.unmodifiableList(activeNodeActivities);
        inactiveNodeActivities = Collections.unmodifiableList(inactiveNodeActivities);
    }

    /**
     * Creates a new instance of {@code NodeRewardGroups} by partitioning the given
     * reward-eligible activities into active and inactive groups. Declining nodes must
     * be excluded from {@code eligibleActivities} before calling this method.
     *
     * <p>The current {@link NodeActivityCriteria} (set via {@link #setActivityCriteria}) is
     * used to classify each node. By default, this checks the missed-rounds threshold.
     *
     * @param eligibleActivities the reward-eligible node activities (declining nodes excluded).
     * @return a {@code NodeRewardGroups} instance partitioning nodes into active and inactive
     */
    public static NodeRewardGroups from(@NonNull final List<NodeRewardActivity> eligibleActivities) {
        requireNonNull(eligibleActivities);

        // Process the activities sorted by node id.
        // Important to ensure determinism of the order of elements in each set.
        final var sortedActivities = new ArrayList<>(eligibleActivities);
        sortedActivities.sort(comparingLong(NodeRewardActivity::nodeId));

        final var active = new ArrayList<NodeRewardActivity>(sortedActivities.size());
        final var inactive = new ArrayList<NodeRewardActivity>(sortedActivities.size());

        for (final var activity : sortedActivities) {
            if (activityCriteria.isActive(activity)) {
                active.add(activity);
            } else {
                inactive.add(activity);
            }
        }
        return new NodeRewardGroups(active, inactive);
    }

    /**
     * Returns the list of active node IDs.
     *
     * @return the list of active node IDs
     */
    public List<Long> activeNodeIds() {
        return activeNodeActivities.stream().map(NodeRewardActivity::nodeId).toList();
    }

    /**
     * Returns the list of inactive node IDs.
     *
     * @return the list of inactive node IDs
     */
    public List<Long> inactiveNodeIds() {
        return inactiveNodeActivities.stream().map(NodeRewardActivity::nodeId).toList();
    }

    /**
     * Returns the list of active node account IDs.
     *
     * @return the list of active node account IDs
     */
    public List<AccountID> activeNodeAccountIds() {
        return activeNodeActivities.stream().map(NodeRewardActivity::accountId).toList();
    }

    /**
     * Returns the list of inactive node account IDs.
     *
     * @return the list of inactive node account IDs
     */
    public List<AccountID> inactiveNodeAccountIds() {
        return inactiveNodeActivities.stream()
                .map(NodeRewardActivity::accountId)
                .toList();
    }
}
