// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Default implementation of {@link ReadableStakingInfoStore}.
 */
public class ReadableStakingInfoStoreImpl implements ReadableStakingInfoStore {

    /** The underlying data storage class that holds node staking data. */
    private final ReadableKVState<EntityNumber, StakingNodeInfo> stakingInfoState;

    /**
     * Create a new {@link ReadableStakingInfoStoreImpl} instance.
     * @param states The state to use.
     */
    public ReadableStakingInfoStoreImpl(@NonNull final ReadableStates states) {
        this.stakingInfoState = states.get(STAKING_INFO_KEY);
    }

    @Nullable
    @Override
    public StakingNodeInfo get(final long nodeId) {
        return stakingInfoState.get(EntityNumber.newBuilder().number(nodeId).build());
    }

    @NonNull
    @Override
    public Set<Long> getAll() {
        // For entity types that have many instances this code would be a bad idea, but for node staking info there
        // should only be a limited number of staking nodes in state. Iterating over all of them should not be expensive
        final var keysIter = stakingInfoState.keys();
        if (!keysIter.hasNext()) return Collections.emptySet();

        final var nodeIds = new HashSet<Long>();
        while (keysIter.hasNext()) {
            nodeIds.add(keysIter.next().number());
        }

        return nodeIds;
    }
}
