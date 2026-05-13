// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.blockDirFor;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Schema that prepares for the block stream cutover during migration. It reads the final record-stream
 * {@link BlockInfo} and {@link RunningHashes} from shared values (populated by {@code V0560BlockRecordSchema})
 * and deletes preview block files before the platform starts writing real post-cutover blocks.
 */
public class V0740BlockStreamSchema extends Schema<SemanticVersion> {

    private static final Logger log = LogManager.getLogger(V0740BlockStreamSchema.class);

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(74).patch(0).build();

    public V0740BlockStreamSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        if (ctx.isGenesis()) {
            log.info("Genesis state, skipping cutover logic");
            return;
        }
        final var config = ctx.appConfig().getConfigData(BlockStreamConfig.class);
        if (!config.enableCutover()) {
            log.info("Cutover disabled by config, skipping cutover logic");
            return;
        }
        final var blockInfo = (BlockInfo) ctx.sharedValues().get(SHARED_BLOCK_RECORD_INFO);
        if (blockInfo == null || blockInfo.previewStreamOverwritten()) {
            log.info("Preview block stream info already overwritten, skipping cutover logic");
            return;
        }

        log.info("Preparing preview stream overwrite for block streams cutover");

        final var runningHashes =
                (RunningHashes) requireNonNull(ctx.sharedValues().get(SHARED_RUNNING_HASHES));
        log.info(
                """
                        Cutover final BlockInfo:
                          lastBlockNumber={}
                          blockHashesLength={}
                          previousWrappedRecordBlockRootHash={}
                          wrappedIntermediateCount={}
                          wrappedIntermediateLeafCount={}
                          firstConsTimeOfCurrentBlock={}
                          lastUsedConsTime={}
                          consTimeOfLastHandledTxn={}
                          lastIntervalProcessTime={}""",
                blockInfo.lastBlockNumber(),
                blockInfo.blockHashes().length(),
                blockInfo.previousWrappedRecordBlockRootHash().toHex(),
                blockInfo.wrappedIntermediatePreviousBlockRootHashes().size(),
                blockInfo.wrappedIntermediateBlockRootsLeafCount(),
                blockInfo.firstConsTimeOfCurrentBlock(),
                blockInfo.lastUsedConsTime(),
                blockInfo.consTimeOfLastHandledTxn(),
                blockInfo.lastIntervalProcessTime());
        log.info(
                """
                        Cutover final RunningHashes:
                          runningHash={}
                          nMinus1={}
                          nMinus2={}
                          nMinus3={}""",
                runningHashes.runningHash().toHex(),
                runningHashes.nMinus1RunningHash().toHex(),
                runningHashes.nMinus2RunningHash().toHex(),
                runningHashes.nMinus3RunningHash().toHex());

        final var cutoverConfig = ctx.appConfig();
        final var blockDirPath = blockDirFor(cutoverConfig);
        log.info("Cutover deleting all preview block files from {}", blockDirPath);
        try (var paths = Files.walk(blockDirPath, 2)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        final var name = p.getFileName().toString();
                        return name.endsWith(".blk.gz")
                                || name.endsWith(".mf")
                                || name.endsWith(".pnd.gz")
                                || name.endsWith(".pnd.json");
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete preview block file: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to remove preview block files", e);
        }
    }
}
