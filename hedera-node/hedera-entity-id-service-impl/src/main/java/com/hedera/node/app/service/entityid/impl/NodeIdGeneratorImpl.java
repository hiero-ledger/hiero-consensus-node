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

    @Inject
    public NodeIdGeneratorImpl(@NonNull final WritableEntityIdStore entityIdStore) {
        this.entityIdStore = requireNonNull(entityIdStore);
    }

    @Override
    public long newNodeId() {
        return entityIdStore.incrementHighestNodeIdAndGet();
    }

    @Override
    public long peekAtNewNodeId() {
        return entityIdStore.peekAtNextNodeId();
    }
}


