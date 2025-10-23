// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service for providing incrementing entity id numbers. It stores the most recent entity id in state.
 */
public abstract class EntityIdService implements Service {
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
}
