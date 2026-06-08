// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NodeRewards;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ReadableNodeRewardsStoreImpl}.
 */
public class WritableNodeRewardsStoreImpl extends ReadableNodeRewardsStoreImpl {
    private static final Logger log = LogManager.getLogger(WritableNodeRewardsStoreImpl.class);

    /**
     * The underlying data storage class that holds node reward data for all nodes.
     */
    private final WritableSingletonState<NodeRewards> nodeRewardsState;

    /**
     * Create a new {@link WritableNodeRewardsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableNodeRewardsStoreImpl(@NonNull final WritableStates states) {
        super(states);
        this.nodeRewardsState = requireNonNull(states).getSingleton(NODE_REWARDS_STATE_ID);
    }

    /**
     * Persists the node rewards data to the underlying storage.
     *
     * @param nodeRewards The node rewards data to persist.
     */
    public void put(@NonNull final NodeRewards nodeRewards) {
        requireNonNull(nodeRewards);
        logNodeRewardsPut(nodeRewardsState.get(), nodeRewards);
        nodeRewardsState.put(nodeRewards);
    }

    /**
     * Resets the node rewards state for a new payment period.
     */
    public void resetForNewStakingPeriod() {
        final var next = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(0)
                .nodeFeesCollected(0)
                .nodeActivities(List.of())
                .build();
        logNodeRewardsPut(nodeRewardsState.get(), next);
        nodeRewardsState.put(next);
    }

    private static void logNodeRewardsPut(@NonNull final NodeRewards previous, @NonNull final NodeRewards next) {
        log.info(
                "Forensic token singleton put TokenService.NODE_REWARDS noOp={} before={} after={}",
                Objects.equals(previous, next),
                nodeRewardsSummary(previous),
                nodeRewardsSummary(next));
    }

    private static String nodeRewardsSummary(@NonNull final NodeRewards nodeRewards) {
        return "activities=%d rounds=%d nodeFeesCollected=%d hashCode=%d"
                .formatted(
                        nodeRewards.nodeActivities().size(),
                        nodeRewards.numRoundsInStakingPeriod(),
                        nodeRewards.nodeFeesCollected(),
                        nodeRewards.hashCode());
    }
}
