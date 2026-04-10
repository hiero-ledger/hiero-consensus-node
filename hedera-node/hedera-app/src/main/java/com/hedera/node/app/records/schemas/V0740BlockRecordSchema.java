// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Migration schema that initializes jumpstart wrapped-record voting metadata once during the 0.74.0 upgrade.
 */
public class V0740BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0740BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(74).patch(0).build();

    public V0740BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()
                && ctx.appConfig().getConfigData(BlockRecordStreamConfig.class).liveWritePrevWrappedRecordHashes()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = blockInfoSingleton.get();
            if (existingBlockInfo == null) {
                log.info("Skipping wrapped record voting initialization because BlockInfo singleton does not exist");
                return;
            }
            if (existingBlockInfo.votingCompletionDeadlineBlockNumber() > 0 || existingBlockInfo.votingComplete()) {
                // A previous upgrade already initialized (or completed) migration voting; don't overwrite the deadline.
                log.info(
                        "BlockInfo wrapped record migration voting state already present (deadlineBlock={}, votingComplete={})",
                        existingBlockInfo.votingCompletionDeadlineBlockNumber(),
                        existingBlockInfo.votingComplete());
            } else if (hasJumpstartData(ctx)) {
                // We only want to initialize jumpstart voting if valid jumpstart data is present
                final long votingCompletionDeadlineBlockNumber = existingBlockInfo.lastBlockNumber() + 10;
                blockInfoSingleton.put(existingBlockInfo
                        .copyBuilder()
                        .votingComplete(false)
                        .votingCompletionDeadlineBlockNumber(votingCompletionDeadlineBlockNumber)
                        .build());
                log.info(
                        "Initialized wrapped record voting singleton with deadline={}",
                        votingCompletionDeadlineBlockNumber);
            }
        } else if (ctx.isGenesis()
                && !hasJumpstartData(ctx)
                && ctx.appConfig().getConfigData(BlockRecordStreamConfig.class).liveWritePrevWrappedRecordHashes()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = blockInfoSingleton.get();
            log.info("Genesis initialization of wrapped record block voting set to complete (no migration)");
            if (existingBlockInfo != null) {
                blockInfoSingleton.put(existingBlockInfo
                        .copyBuilder()
                        .votingComplete(true)
                        .votingCompletionDeadlineBlockNumber(0)
                        .build());
            } else {
                log.warn(
                        "Genesis initialization of wrapped record block voting failed because BlockInfo singleton does not exist");
            }
        }
    }

    private static boolean hasJumpstartData(@NonNull MigrationContext ctx) {
        return ctx.appConfig().getConfigData(BlockStreamJumpstartConfig.class).blockNum() > 0;
    }
}
