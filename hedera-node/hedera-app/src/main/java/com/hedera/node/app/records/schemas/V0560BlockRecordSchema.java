// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

public class V0560BlockRecordSchema extends Schema<SemanticVersion> {
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(56).patch(0).build();

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    public V0560BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (ctx.previousVersion() != null) {
            // Upcoming BlockStreamService schemas may need migration info
            final var blocksState = ctx.newStates().getSingleton(BLOCKS_STATE_ID);
            final var runningHashesState = ctx.newStates().getSingleton(RUNNING_HASHES_STATE_ID);
            ctx.sharedValues().put(SHARED_BLOCK_RECORD_INFO, blocksState.get());
            ctx.sharedValues().put(SHARED_RUNNING_HASHES, runningHashesState.get());
        }
    }
}
