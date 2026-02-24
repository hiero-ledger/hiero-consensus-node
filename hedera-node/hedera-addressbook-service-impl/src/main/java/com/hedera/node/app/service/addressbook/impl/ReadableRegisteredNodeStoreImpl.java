// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableRegisteredNodeStore}.
 */
public class ReadableRegisteredNodeStoreImpl implements ReadableRegisteredNodeStore {
    private final ReadableKVState<EntityNumber, RegisteredNode> registeredNodesState;

    public ReadableRegisteredNodeStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.registeredNodesState = states.get(REGISTERED_NODES_STATE_ID);
    }

    @Override
    @Nullable
    public RegisteredNode get(final long registeredNodeId) {
        return registeredNodesState.get(
                EntityNumber.newBuilder().number(registeredNodeId).build());
    }

    protected <T extends ReadableKVState<EntityNumber, RegisteredNode>> T registeredNodesState() {
        return (T) registeredNodesState;
    }
}
