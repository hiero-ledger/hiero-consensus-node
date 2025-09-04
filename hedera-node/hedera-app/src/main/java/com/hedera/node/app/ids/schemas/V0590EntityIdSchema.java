// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.ids.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0590EntityIdSchema extends Schema {
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(59).patch(0).build();

    public static final String ENTITY_COUNTS_KEY = "ENTITY_COUNTS";

    public V0590EntityIdSchema() {
        super(VERSION);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(ENTITY_COUNTS_KEY, EntityCounts.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            final var entityIdState = ctx.newStates().getSingleton(ENTITY_COUNTS_KEY);
            entityIdState.put(EntityCounts.DEFAULT);
        }
    }
}
