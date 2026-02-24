// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Writable store for registered nodes.
 */
public class WritableRegisteredNodeStore extends ReadableRegisteredNodeStoreImpl {
    public WritableRegisteredNodeStore(@NonNull final WritableStates states) {
        super(states);
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

    public void remove(final long registeredNodeId) {
        registeredNodesState()
                .remove(EntityNumber.newBuilder().number(registeredNodeId).build());
    }
}
