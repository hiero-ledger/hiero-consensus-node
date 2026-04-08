// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.config.data.BlockRecordStreamConfig;
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
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis()
                && ctx.appConfig().getConfigData(BlockRecordStreamConfig.class).liveWritePrevWrappedRecordHashes()) {
            final var blockInfoSingleton = ctx.newStates().<BlockInfo>getSingleton(BLOCKS_STATE_ID);
            final var existingBlockInfo = requireNonNull(blockInfoSingleton.get());
            if (existingBlockInfo.votingCompletionDeadlineBlockNumber() > 0 || existingBlockInfo.votingComplete()) {
                // A previous upgrade already initialized (or completed) migration voting; don't overwrite the deadline.
                log.info(
                        "BlockInfo wrapped record migration voting state already present (deadlineBlock={}, votingComplete={})",
                        existingBlockInfo.votingCompletionDeadlineBlockNumber(),
                        existingBlockInfo.votingComplete());
            } else {
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
        }
    }
}
