// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.records.schemas.V0560BlockRecordSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/**
 * A {@link Service} for managing the state of the running hashes and block information. Used by the
 * {@link BlockRecordManagerImpl}. This service is not exposed outside `hedera-app`.
 */
@Singleton
public final class BlockRecordService implements Service {
    /** The name of this service */
    public static final String NAME = "BlockRecordService";

    /**
     * The epoch timestamp, a placeholder for time of an event that has never happened.
     */
    public static final Timestamp EPOCH = new Timestamp(0, 0);

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490BlockRecordSchema());
        registry.register(new V0560BlockRecordSchema());
    }
}
