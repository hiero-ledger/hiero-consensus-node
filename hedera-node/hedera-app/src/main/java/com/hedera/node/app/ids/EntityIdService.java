// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.ids;

import com.hedera.node.app.ids.schemas.V0490EntityIdSchema;
import com.hedera.node.app.ids.schemas.V0590EntityIdSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service for providing incrementing entity id numbers. It stores the most recent entity id in state.
 */
public class EntityIdService implements Service {
    public static final String NAME = "EntityIdService";

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public int migrationOrder() {
        // Traditionally the entity id service was migrated first, so preserve that harmless convention
        return Integer.MIN_VALUE;
    }

    /** {@inheritDoc} */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490EntityIdSchema());
        registry.register(new V0590EntityIdSchema());
    }
}
