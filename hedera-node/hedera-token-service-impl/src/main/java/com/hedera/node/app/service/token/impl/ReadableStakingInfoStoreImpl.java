// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ReadableStakingInfoStore}.
 */
public class ReadableStakingInfoStoreImpl implements ReadableStakingInfoStore {

    private static final Logger log = LogManager.getLogger(ReadableStakingInfoStoreImpl.class);
    /**
     * The underlying data storage class that holds node staking data.
     */
    private final ReadableKVState<EntityNumber, StakingNodeInfo> stakingInfoState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableStakingInfoStoreImpl} instance.
     *
     * @param states The state to use.
     * @param entityCounters
     */
    public ReadableStakingInfoStoreImpl(
            @NonNull final ReadableStates states, final ReadableEntityCounters entityCounters) {
        this.entityCounters = requireNonNull(entityCounters);
        this.stakingInfoState = states.get(STAKING_INFO_KEY);
    }

    @Nullable
    @Override
    public StakingNodeInfo get(final long nodeId) {
        return stakingInfoState.get(new EntityNumber(nodeId));
    }

    @NonNull
    @Override
    public Set<Long> getAll() {
        final var numStakingInfo = entityCounters.getCounterFor(EntityType.STAKING_INFO);
        if (numStakingInfo == 0) {
            return Collections.emptySet();
        }
        final var nodeIds = new HashSet<Long>();
        for (var i = 0; i < numStakingInfo; i++) {
            final var nodeId = new EntityNumber(i);
            if (stakingInfoState.contains(nodeId)) {
                nodeIds.add(nodeId.number());
            } else {
                log.warn("Staking info for node {} not found in state", nodeId.number());
            }
        }
        return nodeIds;
    }
}
