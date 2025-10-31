// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
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
    private final WritableEntityCounters entityCounters;
    /**
     * Create a new {@link WritableNodeStore} instance.
     *
     * @param states The state to use.
     */
    public WritableNodeStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<EntityNumber, Node> nodesState() {
        return super.nodesState();
    }

    /**
     * Persists an updated {@link Node} into the state, as well as exporting its ID to the transaction
     * receipt.
     * If a node with the same ID already exists, it will be overwritten.
     *
     * @param node - the node to be mapped onto a new {@link Node}
     */
    public void put(@NonNull final Node node) {
        requireNonNull(node);
        nodesState().put(EntityNumber.newBuilder().number(node.nodeId()).build(), node);
    }

    /**
     * Persists a new {@link Node} into the state, as well as exporting its ID to the transaction. It
     * will also increment the entity type count for {@link EntityType#NODE}.
     * @param node - the node to be mapped onto a new {@link Node}
     */
    public void putAndIncrementCount(@NonNull final Node node) {
        put(node);
        entityCounters.incrementEntityTypeCount(EntityType.NODE);
    }

    /**
     * Returns the set of nodes modified in existing state.
     * @return the set of nodes modified in existing state
     */
    public Set<EntityNumber> modifiedNodes() {
        return nodesState().modifiedKeys();
    }
}
