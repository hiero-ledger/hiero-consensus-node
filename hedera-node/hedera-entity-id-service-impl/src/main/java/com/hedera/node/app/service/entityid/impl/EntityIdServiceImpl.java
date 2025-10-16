// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl;

import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service for providing incrementing entity id numbers. It stores the most recent entity id in state.
 */
public class EntityIdServiceImpl extends EntityIdService {

    /** {@inheritDoc} */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490EntityIdSchema());
        registry.register(new V0590EntityIdSchema());
    }
}
