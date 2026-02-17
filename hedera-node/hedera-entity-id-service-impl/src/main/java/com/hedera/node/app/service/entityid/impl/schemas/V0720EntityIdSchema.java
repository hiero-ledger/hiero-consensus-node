// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Minor schema v0.72.0 that introduces a singleton to track the highest node ID ever used.
 */
public class V0720EntityIdSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(72).patch(0).build();

    public static final String NODE_ID_KEY = "NODE_ID";
    public static final int NODE_ID_STATE_ID = SingletonType.ENTITYIDSERVICE_I_NODE_ID.protoOrdinal();
    public static final String NODE_ID_STATE_LABEL = computeLabel(EntityIdService.NAME, NODE_ID_KEY);

    public V0720EntityIdSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(NODE_ID_STATE_ID, NODE_ID_KEY, NodeId.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            final var highestNodeIdState = ctx.newStates().getSingleton(NODE_ID_STATE_ID);
            final var entityCountsState = (EntityCounts)
                    ctx.previousStates().getSingleton(ENTITY_COUNTS_STATE_ID).get();
            highestNodeIdState.put(
                    NodeId.newBuilder().id(entityCountsState.numNodes() - 1).build());
        }
    }
}
