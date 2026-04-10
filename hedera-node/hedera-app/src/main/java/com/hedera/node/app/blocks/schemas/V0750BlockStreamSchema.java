// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.blockDirFor;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Schema that executes the block stream cutover during migration. Reads the final record-stream
 * {@link BlockInfo} and {@link RunningHashes} from shared values (populated by
 * {@code V0560BlockRecordSchema}), reshapes them into the block stream format, and overwrites
 * the {@link BlockStreamInfo} singleton in state.
 */
public class V0750BlockStreamSchema extends Schema<SemanticVersion> {

    private static final Logger log = LogManager.getLogger(V0750BlockStreamSchema.class);

    private static final String SHARED_BLOCK_RECORD_INFO = "SHARED_BLOCK_RECORD_INFO";
    private static final String SHARED_RUNNING_HASHES = "SHARED_RUNNING_HASHES";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(75).patch(0).build();

    private final Runnable cutoverExecutedMarker;

    /**
     * @param cutoverExecutedMarker called when cutover is executed, so the caller can
     *                              mark {@code BlockInfo.cutoverExecuted}
     */
    public V0750BlockStreamSchema(@NonNull final Runnable cutoverExecutedMarker) {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
        this.cutoverExecutedMarker = requireNonNull(cutoverExecutedMarker);
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        if (ctx.isGenesis()) {
            return;
        }
        final var config = ctx.appConfig().getConfigData(BlockStreamConfig.class);
        if (!config.enableCutover()) {
            return;
        }
        final var blockInfo = (BlockInfo) ctx.sharedValues().get(SHARED_BLOCK_RECORD_INFO);
        if (blockInfo == null || blockInfo.cutoverExecuted()) {
            log.info("Block streams cutover not applicable, skipping");
            return;
        }

        log.info("Block streams cutover is enabled, performing cutover in schema migration");

        // Step 1: Gather data from shared values and own state
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

        final var blockStreamInfoState = ctx.newStates().<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
        final var lastBlockStreamInfo = requireNonNull(blockStreamInfoState.get());

        // Step 2: Reshape hashes into the expected block stream format
        // 2.1. Record block hashes (excluding the last hash); BlockHashManager.startBlock() will append
        // prevBlockHash to trailingBlockHashes, so write all but the final record hash to avoid an off-by-one
        final var fullBlockHashes = blockInfo.blockHashes().toByteArray();
        if (fullBlockHashes.length < HASH_SIZE) {
            throw new IllegalStateException(
                    "Cutover requires at least one record block hash in BlockInfo.blockHashes, but found "
                            + fullBlockHashes.length + " bytes (need >= " + HASH_SIZE + ")");
        }
        final Bytes lastBlockHashes = Bytes.wrap(fullBlockHashes, 0, fullBlockHashes.length - HASH_SIZE);
        // 2.2. Running hashes
        Bytes lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus3RunningHash().toByteArray()), Bytes.EMPTY, 4);
        lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus2RunningHash().toByteArray()), lastFourHashes, 4);
        lastFourHashes =
                appendHash(Bytes.wrap(runningHashes.nMinus1RunningHash().toByteArray()), lastFourHashes, 4);
        lastFourHashes = appendHash(Bytes.wrap(runningHashes.runningHash().toByteArray()), lastFourHashes, 4);
        // 2.3. Wrapped prev record block root hashes
        final List<Bytes> wrappedPrevRecordBlockRootHashes = blockInfo.wrappedIntermediatePreviousBlockRootHashes();

        // Step 3: delete preview block files
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

        // Step 4: Overwrite the (preview) block stream info in state
        log.info(
                "Using current preview stream state hash {} as the starting state hash for first block after cutover",
                lastBlockStreamInfo.startOfBlockStateHash());
        final var cutoverBlockStreamInfo = lastBlockStreamInfo
                .copyBuilder()
                .blockNumber(blockInfo.lastBlockNumber())
                .blockTime(blockInfo.firstConsTimeOfCurrentBlock())
                .trailingOutputHashes(lastFourHashes)
                .trailingBlockHashes(lastBlockHashes)
                .inputTreeRootHash(HASH_OF_ZERO)
                .numPrecedingStateChangesItems(0)
                .rightmostPrecedingStateChangesTreeHashes(List.of())
                .blockEndTime(blockInfo.lastUsedConsTime())
                .lastIntervalProcessTime(blockInfo.lastIntervalProcessTime())
                .lastHandleTime(blockInfo.consTimeOfLastHandledTxn())
                .consensusHeaderRootHash(HASH_OF_ZERO)
                .traceDataRootHash(HASH_OF_ZERO)
                .intermediatePreviousBlockRootHashes(wrappedPrevRecordBlockRootHashes)
                .intermediateBlockRootsLeafCount(blockInfo.wrappedIntermediateBlockRootsLeafCount())
                .build();
        blockStreamInfoState.put(cutoverBlockStreamInfo);

        log.info(
                """
                        Cutover initial BlockStreamInfo:
                          blockNumber={}
                          blockTime={}
                          trailingBlockHashes={}
                          trailingOutputHashes={}
                          blockEndTime={}
                          lastIntervalProcessTime={}
                          lastHandleTime={}
                          intermediatePreviousBlockRootHashes={}
                          intermediateBlockRootsLeafCount={}""",
                cutoverBlockStreamInfo.blockNumber(),
                cutoverBlockStreamInfo.blockTime(),
                cutoverBlockStreamInfo.trailingBlockHashes().toHex(),
                cutoverBlockStreamInfo.trailingOutputHashes().toHex(),
                cutoverBlockStreamInfo.blockEndTime(),
                cutoverBlockStreamInfo.lastIntervalProcessTime(),
                cutoverBlockStreamInfo.lastHandleTime(),
                cutoverBlockStreamInfo.intermediatePreviousBlockRootHashes(),
                cutoverBlockStreamInfo.intermediateBlockRootsLeafCount());

        // Signal that cutover was executed
        cutoverExecutedMarker.run();
    }
}
