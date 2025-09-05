// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableAirdropStore}.
 */
public class ReadableAirdropStoreImpl implements ReadableAirdropStore {

    /** The underlying data storage class that holds the airdrop data. */
    private final ReadableKVState<PendingAirdropId, AccountPendingAirdrop> readableAirdropState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableAirdropStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableAirdropStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        this.entityCounters = entityCounters;
        requireNonNull(states);
        this.readableAirdropState = states.get(AIRDROPS_STATE_ID);
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(@NonNull final PendingAirdropId airdropId) {
        return readableAirdropState.contains(requireNonNull(airdropId));
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public AccountPendingAirdrop get(@NonNull final PendingAirdropId airdropId) {
        requireNonNull(airdropId);
        return readableAirdropState.get(airdropId);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.AIRDROP);
    }
}
