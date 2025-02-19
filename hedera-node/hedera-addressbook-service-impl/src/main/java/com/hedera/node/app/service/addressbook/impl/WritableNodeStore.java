/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
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
