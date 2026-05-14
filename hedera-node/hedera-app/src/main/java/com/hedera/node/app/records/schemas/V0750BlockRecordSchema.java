// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Migration schema that increments the last block number and sets the first consensus time of the current block to the epoch timestamp.
 * This is necessary because the mechanism for which record files close is changing in release 0.75, and the the BlockInfo singleton
 * needs to represent the last closed block number, which would be the freeze block number which would previously have been updated
 * when handling the next user transaction after an upgrade.
 */
public class V0750BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0750BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(75).patch(0).build();

    public V0750BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = blockInfoSingleton.get();
            log.info("Migrating BlockInfo singleton with lastBlockNumber " + (existingBlockInfo.lastBlockNumber() + 1)
                    + " and firstConsTimeOfCurrentBlock to EPOCH");
            blockInfoSingleton.put(existingBlockInfo
                    .copyBuilder()
                    .lastBlockNumber(existingBlockInfo.lastBlockNumber() + 1)
                    .firstConsTimeOfCurrentBlock(EPOCH)
                    .build());
        }
    }
}
