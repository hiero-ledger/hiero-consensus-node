// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.entityid.NodeIdGenerator;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Default implementation of {@link NodeIdGenerator} using the highest node id singleton.
 */
public class NodeIdGeneratorImpl implements NodeIdGenerator {

    private final WritableEntityIdStore entityIdStore;

    /**
     * Creates a new instance of {@link NodeIdGeneratorImpl} with the provided entity ID store.
     *
     * @param entityIdStore the writable store used to manage node IDs; must not be {@code null}
     */
    @Inject
    public NodeIdGeneratorImpl(@NonNull final WritableEntityIdStore entityIdStore) {
        this.entityIdStore = requireNonNull(entityIdStore);
    }

    /**
     * Generates and returns a new unique node ID by incrementing the highest node ID.
     *
     * @return the newly generated node ID
     */
    @Override
    public long newNodeId() {
        return entityIdStore.incrementHighestNodeIdAndGet();
    }

    /**
     * Returns the next node ID that would be generated, without incrementing the highest node ID.
     *
     * @return the next available node ID
     */
    @Override
    public long peekAtNewNodeId() {
        return entityIdStore.peekAtNextNodeId();
    }
}
