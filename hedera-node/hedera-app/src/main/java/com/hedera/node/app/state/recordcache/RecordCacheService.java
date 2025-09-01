// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A service representing this record cache facility. Many services are "big S" services like Hedera Consensus Service.
 * Facilities like Record Cache are "little s" services. They have state and schemas, but are not big ticket services
 * marketed to the world.
 */
public class RecordCacheService implements Service {

    /** The record cache service name */
    public static final String NAME = "RecordCache";

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    /** {@inheritDoc} */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490RecordCacheSchema());
    }
}
