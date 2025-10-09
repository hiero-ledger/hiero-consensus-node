// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.addressbook.NodeIdList;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides write methods for modifying underlying data storage mechanisms..
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
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
     * Associates a node with an account by storing the relationship in the state.
     *
     * <p>This method retrieves any existing node ID list for the specified account,
     * creates a new list with the existing node IDs (if any), and updates the state with
     * this node ID list for the given account.
     *
     * @param accountId The account identifier to associate with the node
     * @param nodeId The node identifier to associate with the account
     */
    public void put(@NonNull final AccountID accountId, @NonNull Long nodeId) {
        requireNonNull(accountId);
        requireNonNull(nodeId);
        final var nodeIdList = get(accountId);
        List<Long> newList = new ArrayList<>();
        if (nodeIdList != null) {
            newList = nodeIdList.nodeId();
        }
        newList.add(nodeId);
        accountNodeRelState()
                .put(accountId, NodeIdList.newBuilder().nodeId(newList).build());
    }

    /**
     * Associates a node with an account by storing the relationship in the state and increments
     * the entity count for the NODE entity type.
     *
     * <p>This method calls {@link #put(AccountID, Long)} to store the account-node relationship
     * and then increments the entity type count to track the number of nodes in the system.
     *
     * @param accountId The account identifier to associate with the node
     * @param nodeId The node identifier to associate with the account
     */
    public void putAndIncrementCount(@NonNull final AccountID accountId, @NonNull Long nodeId) {
        requireNonNull(accountId);
        requireNonNull(nodeId);
        put(accountId, nodeId);
        entityCounters.incrementEntityTypeCount(EntityType.ACCOUNT_NODE_REL);
    }

    /**
     * Removes all node associations for the specified account from the state.
     *
     * <p>This method deletes any node ID list associated with the given account ID,
     * effectively removing all relationships between the account and any nodes.
     *
     * @param accountId The account identifier whose node relationships should be removed
     * @throws NullPointerException if accountId is null
     */
    public void remove(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        accountNodeRelState().remove(accountId);
    }
}
