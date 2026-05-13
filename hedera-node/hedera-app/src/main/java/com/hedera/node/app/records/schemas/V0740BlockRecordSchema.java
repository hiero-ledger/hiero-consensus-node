// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Migration schema that makes the existing block record info and running hashes available as shared values for
 * the upcoming jumpstart cutover (if applicable).
 */
public class V0740BlockRecordSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(74).patch(0).build();

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    public V0740BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        if (ctx.isGenesis()
                || !ctx.appConfig().getConfigData(BlockStreamConfig.class).enableCutover()) {
            return;
        }

        final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
        ctx.sharedValues().put(SHARED_BLOCK_RECORD_INFO, blockInfoSingleton.get());
        ctx.sharedValues()
                .put(
                        SHARED_RUNNING_HASHES,
                        ctx.newStates().getSingleton(RUNNING_HASHES_STATE_ID).get());
    }
}
