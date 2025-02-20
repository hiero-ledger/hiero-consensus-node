// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.recordcache.TransactionRecordEntry;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0490RecordCacheSchema extends Schema {
    /** The name of the queue that stores the transaction records */
    public static final String TXN_RECORD_QUEUE = "TransactionRecordQueue";
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public V0490RecordCacheSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.queue(V0490RecordCacheSchema.TXN_RECORD_QUEUE, TransactionRecordEntry.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // No genesis records
    }
}
