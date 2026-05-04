// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
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
 * Migration schema that initializes jumpstart wrapped-record voting metadata once during the 0.74.0 upgrade.
 * It also makes the existing block record info and running hashes available as shared values for the upcoming
 * jumpstart cutover (if applicable).
 */
public class V0740BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0740BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(74).patch(0).build();
    public static final int DEADLINE_BLOCK_NUMBER_BUFFER = 10;

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    public V0740BlockRecordSchema() {
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
                // We only want to initialize jumpstart voting if valid jumpstart data is present
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
}
