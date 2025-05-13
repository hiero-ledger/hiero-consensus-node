// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.StakingNodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with node staking infos.
 */
public interface ReadableStakingInfoStore {
    /**
     * Fetches a {@link StakingNodeInfo} object from state with the given node ID. If the node could not be
     * fetched because the given node doesn't exist, returns {@code null}.
     *
     * @param nodeId the given node ID
     * @return {@link StakingNodeInfo} object if successfully fetched or {@code null} if the node doesn't exist
     */
    @Nullable
    StakingNodeInfo get(long nodeId);

    /***
     * Returns a function mapping undeleted node ids to their weights in this store.
     */
    default LongUnaryOperator weightFunction() {
        return nodeId -> {
            final var stakingInfo = get(nodeId);
            if (stakingInfo != null && !stakingInfo.deleted()) {
                return stakingInfo.stake();
            }
            return 0L;
        };
    }

    /**
     * Fetches all node IDs from state. If no nodes exist, returns an empty collection.
     *
     * <p>
     * ⚠️⚠️
     * WARNING:
     * This method only exists because staking state is small. Don't follow this pattern in other stores
     * without careful consideration!
     * ⚠️⚠️
     *
     * @return a set of all registered node IDs
     */
    @NonNull
    Set<Long> getAll();
}
