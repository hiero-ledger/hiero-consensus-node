// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Writable store for registered nodes.
 */
public class WritableRegisteredNodeStore extends ReadableRegisteredNodeStoreImpl {
    private final WritableEntityIdStore writableEntityIdStore;

    public WritableRegisteredNodeStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityIdStore writableEntityIdStore) {
        super(states);
        this.writableEntityIdStore = requireNonNull(writableEntityIdStore);
    }

    @Override
    protected WritableKVState<EntityNumber, RegisteredNode> registeredNodesState() {
        return super.registeredNodesState();
    }

    public void put(@NonNull final RegisteredNode node) {
        requireNonNull(node);
        registeredNodesState()
                .put(EntityNumber.newBuilder().number(node.registeredNodeId()).build(), node);
    }

    /**
     * Persists a new {@link RegisteredNode} into the state and increments the entity type count
     * for {@link EntityType#REGISTERED_NODE}.
     *
     * @param node the registered node to store
     */
    public void putAndIncrementCount(@NonNull final RegisteredNode node) {
        put(node);
        writableEntityIdStore.incrementEntityTypeCount(EntityType.REGISTERED_NODE);
    }

    /**
     * Removes a registered node from state and decrements the registered node entity count.
     *
     * @param registeredNodeId the ID of the registered node to remove
     */
    public void remove(final long registeredNodeId) {
        registeredNodesState()
                .remove(EntityNumber.newBuilder().number(registeredNodeId).build());
        writableEntityIdStore.decrementEntityTypeCounter(EntityType.REGISTERED_NODE);
    }
}
