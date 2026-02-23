// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with Pending Airdrops.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableAirdropStore extends ReadableAirdropStoreImpl {

    /**
     * The underlying data storage class that holds the Pending Airdrops data.
     */
    private final WritableKVState<PendingAirdropId, AccountPendingAirdrop> airdropState;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableAirdropStore} instance.
     *
     * @param states The state to use.
     */
    public WritableAirdropStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        airdropState = states.get(AIRDROPS_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Persists a new {@link PendingAirdropId} with given {@link AccountPendingAirdrop} into the state.
     * This always replaces the existing value.
     *
     * @param airdropId    - the airdropId to be persisted.
     * @param accountAirdrop - the account airdrop mapping for the given airdropId to be persisted.
     */
    public void put(@NonNull final PendingAirdropId airdropId, @NonNull final AccountPendingAirdrop accountAirdrop) {
        requireNonNull(airdropId);
        requireNonNull(accountAirdrop);
        airdropState.put(airdropId, accountAirdrop);
    }

    /**
     * Persists a new {@link PendingAirdropId} with given {@link AccountPendingAirdrop} into the state.
     * It also increments the entity counts for {@link EntityType#AIRDROP}.
     * @param airdropId the airdropId to be persisted
     * @param accountAirdrop the account airdrop mapping for the given airdropId to be persisted
     */
    public void putAndIncrementCount(
            @NonNull final PendingAirdropId airdropId, @NonNull final AccountPendingAirdrop accountAirdrop) {
        put(airdropId, accountAirdrop);
        entityCounters.incrementEntityTypeCount(EntityType.AIRDROP);
    }

    /**
     * Removes a {@link PendingAirdropId} from the state.
     *
     * @param airdropId the {@code PendingAirdropId} to be removed
     */
    public void remove(@NonNull final PendingAirdropId airdropId) {
        airdropState.remove(requireNonNull(airdropId));
        entityCounters.decrementEntityTypeCounter(EntityType.AIRDROP);
    }

    public boolean contains(final PendingAirdropId pendingId) {
        return airdropState.contains(pendingId);
    }
}
