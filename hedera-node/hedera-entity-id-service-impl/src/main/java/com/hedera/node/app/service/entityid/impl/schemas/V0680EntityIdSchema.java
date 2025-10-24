// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Minor schema v0.68.0 that introduces a singleton to track the highest node ID ever used.
 */
public class V0680EntityIdSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();

    public static final String HIGHEST_NODE_ID_KEY = "HIGHEST_NODE_ID";
    public static final int HIGHEST_NODE_ID_STATE_ID = SingletonType.ENTITYIDSERVICE_I_HIGHEST_NODE_ID.protoOrdinal();
    public static final String HIGHEST_NODE_ID_STATE_LABEL = computeLabel(EntityIdService.NAME, HIGHEST_NODE_ID_KEY);

    public V0680EntityIdSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(HIGHEST_NODE_ID_STATE_ID, HIGHEST_NODE_ID_KEY, EntityNumber.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // Initialize the highest node id to the maximum node id present in the address book at migration time,
        // scanning a fixed range of [0, 100).
        final var highestNodeIdState = ctx.newStates().getSingleton(HIGHEST_NODE_ID_STATE_ID);
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
    }
}
