// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Migration schema for release 0.76.
 *
 * <p>{@link #migrate(MigrationContext)} performs the one-shot cleanup of the stale on-disk
 * {@value com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter#DEFAULT_FILE_NAME}
 * file when {@code writeWrappedRecordFileBlockHashesToDisk} is disabled.
 *
 * <p>{@link #restart(MigrationContext)} carries over the cutover/jumpstart logic from
 * {@link V0750BlockRecordSchema}: the platform invokes {@code restart()} only on the latest schema
 * per service, so without this carry-over the shared values consumed by
 * {@code V0740BlockStreamSchema} and the jumpstart voting initialization would silently stop
 * running on the 0.75 → 0.76 boundary.
 */
public class V0760BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0760BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(76).patch(0).build();

    private static final int DEADLINE_BLOCK_NUMBER_BUFFER = 10;

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    public V0760BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void migrate(@NonNull final MigrationContext<SemanticVersion> ctx) {
        if (ctx.isGenesis()) {
            return;
        }
        final var cfg = ctx.appConfig().getConfigData(BlockRecordStreamConfig.class);
        if (cfg.writeWrappedRecordFileBlockHashesToDisk()) {
            return;
        }
        final Path file = Paths.get(cfg.wrappedRecordHashesDir()).resolve(DEFAULT_FILE_NAME);
        try {
            if (Files.deleteIfExists(file)) {
                log.info("Deleted stale wrapped record hashes file {}", file);
            }
        } catch (final IOException e) {
            log.warn("Failed to delete stale wrapped record hashes file {}", file, e);
        }
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
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

    private static boolean hasJumpstartData(@NonNull final MigrationContext<SemanticVersion> ctx) {
        return ctx.appConfig().getConfigData(BlockStreamJumpstartConfig.class).blockNum() > 0;
    }
}
