// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.addressbook.NodeIdList;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableAccountNodeRelStore extends ReadableAccountNodeRelStoreImpl {
    private final WritableEntityCounters entityCounters;
    /**
     * Create a new {@link WritableAccountNodeRelStore} instance.
     *
     * @param states The state to use.
     */
    public WritableAccountNodeRelStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<AccountID, NodeIdList> accountNodeRelState() {
        return super.accountNodeRelState();
    }

    /**
     * Persists an updated {@link Node} into the state, as well as exporting its ID to the transaction
     * receipt.
     * If a node with the same ID already exists, it will be overwritten.
     *
     * @param node - the node to be mapped onto a new {@link Node}
     */
    public void put(@NonNull final AccountID accountId, @NonNull Long nodeId) {
        requireNonNull(accountId);
        requireNonNull(nodeId);
        final var nodeIdList = get(accountId);
        List<Long> newList = new ArrayList<>();
        if (nodeIdList != null) {
            newList = nodeIdList.nodeId();
        }
        accountNodeRelState()
                .put(accountId, NodeIdList.newBuilder().nodeId(newList).build());
    }

    /**
     * Persists a new {@link Node} into the state, as well as exporting its ID to the transaction. It
     * will also increment the entity type count for {@link EntityType#NODE}.
     * @param node - the node to be mapped onto a new {@link Node}
     */
    public void putAndIncrementCount(@NonNull final AccountID accountId, @NonNull Long nodeId) {
        requireNonNull(accountId);
        requireNonNull(nodeId);
        put(accountId, nodeId);
        entityCounters.incrementEntityTypeCount(EntityType.NODE);
    }

    public void remove(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        accountNodeRelState().remove(accountId);
        // TODO export this to own store and decrement relation entity counter!!!! See: WritableTokenRelationStore
    }
}
