// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.entityid.impl.schemas.V0690EntityIdSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides migration logic to populate the highest node id and removing deleted nodes from state.
 */
public class V069AddressBookSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(69).patch(0).build();

    /**
     * Constructs a new schema instance for version 0.68.0,
     * using the semantic version comparator for version management.
     */
    public V069AddressBookSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            // 1) Initialize the highest node id from previous nodes in [0,100)
            final var highestNodeIdState = ctx.newStates().getSingleton(V0690EntityIdSchema.HIGHEST_NODE_ID_STATE_ID);
            long highest = -1;
            if (highestNodeIdState.get() == null) {
                final var prevNodes = ctx.previousStates().get(V053AddressBookSchema.NODES_STATE_ID);
                for (long i = 0; i < 100; i++) {
                    final var key = EntityNumber.newBuilder().number(i).build();
                    final var node = prevNodes.get(key);
                    if (node != null) {
                        highest = Math.max(highest, i);
                    }
                }
                highestNodeIdState.put(NodeId.newBuilder().id(highest).build());
            }

            // 2) Remove any nodes with deleted=true, scanning ids in [0, 100)
            final var nodes = ctx.newStates().get(V053AddressBookSchema.NODES_STATE_ID);
            for (long i = 0; i < 100; i++) {
                final var key = EntityNumber.newBuilder().number(i).build();
                final var node = (Node) nodes.get(key);
                if (node != null && node.deleted()) {
                    nodes.remove(key);
                }
            }
        }
    }
}
