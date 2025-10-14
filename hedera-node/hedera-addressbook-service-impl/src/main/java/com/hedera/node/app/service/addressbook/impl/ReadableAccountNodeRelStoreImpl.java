// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.addressbook.ReadableAccountNodeRelStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class ReadableAccountNodeRelStoreImpl implements ReadableAccountNodeRelStore {

    private final ReadableKVState<AccountID, Long> accountNodeRelState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableAccountNodeRelStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableAccountNodeRelStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.accountNodeRelState = states.get(ACCOUNT_NODE_REL_STATE_ID);
    }

    protected <T extends ReadableKVState<AccountID, Long>> T accountNodeRelState() {
        return (T) accountNodeRelState;
    }

    /**
     * Returns the node identifier linked to the provided account.
     * Returns null if relations are not found.
     *
     * @param accountId being looked up
     * @return node identifier
     */
    @Override
    @Nullable
    public Long get(final AccountID accountId) {
        return accountNodeRelState.get(accountId);
    }

    /**
     * Returns the number of relations in the state.
     *
     * @return the number of relations in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.ACCOUNT_NODE_REL);
    }
}
