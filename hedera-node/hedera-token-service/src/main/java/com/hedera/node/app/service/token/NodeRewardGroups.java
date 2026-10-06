// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
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
            if (activity.isActive()) {
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
