// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema.ACCOUNT_NODE_REL_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.addressbook.ReadableAccountNodeRelStore;
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

    private final ReadableKVState<AccountID, NodeId> accountNodeRelState;

    /**
     * Create a new {@link ReadableAccountNodeRelStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableAccountNodeRelStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.accountNodeRelState = states.get(ACCOUNT_NODE_REL_STATE_ID);
    }

    protected <T extends ReadableKVState<AccountID, NodeId>> T accountNodeRelState() {
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
        final var nodeId = accountNodeRelState.get(accountId);
        return nodeId != null ? nodeId.id() : null;
    }
}
