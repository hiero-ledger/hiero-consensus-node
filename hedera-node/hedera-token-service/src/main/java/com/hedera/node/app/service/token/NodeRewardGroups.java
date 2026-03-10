// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        @NonNull Set<NodeRewardActivity> activeNodeActivities,
        @NonNull Set<NodeRewardActivity> inactiveNodeActivities) {

    /**
     * Creates a new instance of {@code NodeRewardGroups} and ensures the activity sets are
     * unmodifiable.
     *
     * @param activeNodeActivities   the set of active node activities.
     * @param inactiveNodeActivities the set of inactive node activities.
     */
    public NodeRewardGroups {
        activeNodeActivities = Collections.unmodifiableSet(activeNodeActivities);
        inactiveNodeActivities = Collections.unmodifiableSet(inactiveNodeActivities);
    }

    /**
     * Creates a new instance of {@code NodeRewardGroups} by partitioning the given
     * reward-eligible activities into active and inactive groups. Declining nodes must
     * be excluded from {@code eligibleActivities} before calling this method.
     *
     * @param eligibleActivities the reward-eligible node activities (declining nodes excluded).
     * @return a {@code NodeRewardGroups} instance partitioning nodes into active and inactive.
     */
    public static NodeRewardGroups from(@NonNull final List<NodeRewardActivity> eligibleActivities) {
        requireNonNull(eligibleActivities);

        final var active = new HashSet<NodeRewardActivity>();
        final var inactive = new HashSet<NodeRewardActivity>();

        for (final var activity : eligibleActivities) {
            if (activity.isActive()) {
                active.add(activity);
            } else {
                inactive.add(activity);
            }
        }
        return new NodeRewardGroups(active, inactive);
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
