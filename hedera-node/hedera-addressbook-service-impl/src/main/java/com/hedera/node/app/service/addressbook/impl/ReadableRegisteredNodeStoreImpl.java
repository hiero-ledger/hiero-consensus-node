// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableRegisteredNodeStore}.
 */
public class ReadableRegisteredNodeStoreImpl implements ReadableRegisteredNodeStore {
    private final ReadableKVState<EntityNumber, RegisteredNode> registeredNodesState;
    protected final ReadableEntityIdStore entityIdStore;

    public ReadableRegisteredNodeStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityIdStore entityIdStore) {
        requireNonNull(states);
        this.entityIdStore = requireNonNull(entityIdStore);
        this.registeredNodesState = states.get(REGISTERED_NODES_STATE_ID);
    }

    @Override
    @Nullable
    public RegisteredNode get(final long registeredNodeId) {
        return registeredNodesState.get(
                EntityNumber.newBuilder().number(registeredNodeId).build());
    }

    @Override
    public long peekAtNextNodeId() {
        return entityIdStore.peekAtNextNodeId();
    }

    protected <T extends ReadableKVState<EntityNumber, RegisteredNode>> T registeredNodesState() {
        return (T) registeredNodesState;
    }
}
