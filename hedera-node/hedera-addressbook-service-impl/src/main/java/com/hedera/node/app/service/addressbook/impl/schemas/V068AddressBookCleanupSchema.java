// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.common.EntityNumberOrBuilder;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.service.entityid.impl.schemas.V0680EntityIdSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Minor schema v0.68.0 for AddressBook service to remove any nodes marked deleted from the state.
 */
public class V068AddressBookCleanupSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    public V068AddressBookCleanupSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of();
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // 1) Initialize highest node id from previous nodes in [0,100)
        final var highestNodeIdState = ctx.newStates().getSingleton(V0680EntityIdSchema.HIGHEST_NODE_ID_STATE_ID);
        if (highestNodeIdState.get() == null) {
            long highest = -1;
            final var prevNodes = ctx.previousStates().get(V053AddressBookSchema.NODES_STATE_ID);
            for (long i = 0; i < 100; i++) {
                final var key = EntityNumber.newBuilder().number(i).build();
                final var node = prevNodes.get(key);
                if (node != null) {
                    highest = Math.max(highest, i);
                }
            }
            highestNodeIdState.put(EntityNumber.newBuilder().number(highest).build());
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
