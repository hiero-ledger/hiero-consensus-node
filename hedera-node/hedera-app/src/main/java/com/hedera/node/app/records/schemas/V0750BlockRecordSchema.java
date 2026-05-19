// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Migration schema that initializes jumpstart wrapped-record voting metadata during an upgrade if the jumpstart data is present.
 * It also makes the existing block record info and running hashes available as shared values for the upcoming
 * jumpstart cutover (if applicable).
 * <p>
 * Also contains a migrate method that increments the last block number and sets the first consensus time of the current block to the epoch timestamp.
 * This is necessary because the mechanism for which record files close is changing in release 0.75, and the BlockInfo singleton
 * needs to represent the last closed block number, which would be the freeze block number which would previously have been updated
 * when handling the next user transaction after an upgrade.
 */
public class V0750BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0750BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(75).patch(0).build();

    public static final int DEADLINE_BLOCK_NUMBER_BUFFER = 10;

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    public V0750BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()
                && ctx.isUpgrade(ctx.appConfig()
                        .getConfigData(VersionConfig.class)
                        .servicesVersion()
                        .copyBuilder()
                        .build(""
                                + ctx.appConfig()
                                        .getConfigData(HederaConfig.class)
                                        .configVersion())
                        .build())
                && ctx.appConfig().getConfigData(BlockRecordStreamConfig.class).liveWritePrevWrappedRecordHashes()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = blockInfoSingleton.get();
            if (existingBlockInfo == null) {
                log.info("Skipping wrapped record voting initialization because BlockInfo singleton does not exist");
                return;
            }
            if (hasJumpstartData(ctx)) {
                final long votingCompletionDeadlineBlockNumber =
                        existingBlockInfo.lastBlockNumber() + DEADLINE_BLOCK_NUMBER_BUFFER;
                blockInfoSingleton.put(existingBlockInfo
                        .copyBuilder()
                        .votingComplete(false)
                        .votingCompletionDeadlineBlockNumber(votingCompletionDeadlineBlockNumber)
                        .migrationRootHashVotes(List.of())
                        .build());
                log.info(
                        "Initialized wrapped record voting singleton with deadline={}",
                        votingCompletionDeadlineBlockNumber);
            }
        }
        if (!ctx.isGenesis()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            if (ctx.appConfig().getConfigData(BlockStreamConfig.class).enableCutover()) {
                ctx.sharedValues().put(SHARED_BLOCK_RECORD_INFO, blockInfoSingleton.get());
                ctx.sharedValues()
                        .put(
                                SHARED_RUNNING_HASHES,
                                ctx.newStates()
                                        .getSingleton(RUNNING_HASHES_STATE_ID)
                                        .get());
            }
        }
    }

    private static boolean hasJumpstartData(@NonNull MigrationContext ctx) {
        return ctx.appConfig().getConfigData(BlockStreamJumpstartConfig.class).blockNum() > 0;
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = blockInfoSingleton.get();
            if (existingBlockInfo == null) {
                log.info("Skipping BlockInfo migration because BlockInfo singleton does not exist");
                return;
            }
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
