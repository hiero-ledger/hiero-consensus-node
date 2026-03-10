// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableNodeStore extends ReadableNodeStoreImpl {
    private final WritableEntityIdStore writableEntityIdStore;
    /**
     * Create a new {@link WritableNodeStore} instance.
     *
     * @param states The state to use.
     */
    public WritableNodeStore(@NonNull final WritableStates states, @NonNull final WritableEntityIdStore entityIdStore) {
        super(states, entityIdStore);
        this.writableEntityIdStore = entityIdStore;
    }

    @Override
    protected WritableKVState<EntityNumber, Node> nodesState() {
        return super.nodesState();
    }

    /**
     * Persists a {@link Node} into the state. If a node with the same ID already exists,
     * it will be overwritten. Does not modify entity counts or the highest node ID.
     *
     * @param node the node to persist
     */
    public void put(@NonNull final Node node) {
        requireNonNull(node);
        nodesState().put(EntityNumber.newBuilder().number(node.nodeId()).build(), node);
    }

    /**
     * Persists a new {@link Node} into the state. Increments both the highest node ID
     * and the {@link EntityType#NODE} entity type count.
     *
     * @param node the node to persist
     */
    public void putAndIncrement(@NonNull final Node node) {
        requireNonNull(node);
        put(node);
        writableEntityIdStore.incrementHighestNodeIdAndGet();
        writableEntityIdStore.incrementEntityTypeCount(EntityType.NODE);
    }

    /**
     * Updates the highest node ID tracked in state to match the given node's ID,
     * but only if it is greater than the current highest. This is needed for
     * system-dispatched node creations (e.g. transplant) where the node ID is
     * explicitly provided and may exceed the sequentially incremented highest ID.
     *
     * @param node the node whose ID may become the new highest
     */
    public void updateHighestNodeIdIfLarger(@NonNull final Node node) {
        writableEntityIdStore.updateHighestNodeIdIfLarger(node.nodeId());
    }

    /**
     * Removes the node with the given id from state and decrements the node entity count.
     * @param nodeId the node id to remove
     */
    public void remove(final long nodeId) {
        nodesState().remove(EntityNumber.newBuilder().number(nodeId).build());
        writableEntityIdStore.decrementEntityTypeCounter(EntityType.NODE);
    }

    /**
     * Returns the set of nodes modified in existing state.
     * @return the set of nodes modified in existing state
     */
    public Set<EntityNumber> modifiedNodes() {
        return nodesState().modifiedKeys();
    }
}
