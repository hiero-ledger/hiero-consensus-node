// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.impl.BlockStreamCutover;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.blocks.schemas.V0740BlockStreamSchema;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Service for BlockStreams implementation responsible for tracking state changes
 * and writing them to a block
 */
public class BlockStreamService implements Service {
    private static final Logger log = LogManager.getLogger(BlockStreamService.class);

    /**
     * The block stream manager increments the previous number when starting a block; so to start
     * the genesis block number at {@code 0}, we set the "previous" number to {@code -1}.
     */
    public static final BlockStreamInfo GENESIS_BLOCK_STREAM_INFO =
            BlockStreamInfo.newBuilder().blockNumber(-1).build();

    public static final String NAME = "BlockStreamService";

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0560BlockStreamSchema());
        registry.register(new V0740BlockStreamSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID).put(GENESIS_BLOCK_STREAM_INFO);
        return true;
    }

    @Override
    public boolean doPostUpgradeSetup(
            @NonNull final WritableStates writableStates, @NonNull final PostUpgradeContext context) {
        requireNonNull(writableStates);
        requireNonNull(context);
        if (!context.configuration().getConfigData(BlockStreamConfig.class).enableCutover()
                || !writableStates.contains(BLOCK_STREAM_INFO_STATE_ID)) {
            return false;
        }

        final var blockRecordStates = context.readableStates(BlockRecordService.NAME);
        if (!blockRecordStates.contains(BLOCKS_STATE_ID) || !blockRecordStates.contains(RUNNING_HASHES_STATE_ID)) {
            return false;
        }
        final var blockInfo =
                blockRecordStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).get();
        if (blockInfo == null) {
            return false;
        }

        final var blockStreamInfoState = writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
        final var currentBlockStreamInfo = blockStreamInfoState.get();
        if (currentBlockStreamInfo == null) {
            return false;
        }
        if (blockInfo.previewStreamOverwritten()
                && currentBlockStreamInfo.blockNumber() != blockInfo.lastBlockNumber()) {
            return false;
        }

        final var runningHashes = blockRecordStates
                .<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID)
                .get();
        if (runningHashes == null) {
            return false;
        }

        final var cutoverBlockStreamInfo =
                BlockStreamCutover.blockStreamInfoFrom(blockInfo, runningHashes, currentBlockStreamInfo);
        if (cutoverBlockStreamInfo.equals(currentBlockStreamInfo)) {
            return false;
        }
        log.info(
                "Using current preview stream state hash {} as the starting state hash for first block after cutover",
                currentBlockStreamInfo.startOfBlockStateHash());
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
                          startOfBlockStateHash={}
                          intermediatePreviousBlockRootHashes={}
                          intermediateBlockRootsLeafCount={}""",
                cutoverBlockStreamInfo.blockNumber(),
                cutoverBlockStreamInfo.blockTime(),
                cutoverBlockStreamInfo.trailingBlockHashes().toHex(),
                cutoverBlockStreamInfo.trailingOutputHashes().toHex(),
                cutoverBlockStreamInfo.blockEndTime(),
                cutoverBlockStreamInfo.lastIntervalProcessTime(),
                cutoverBlockStreamInfo.lastHandleTime(),
                cutoverBlockStreamInfo.startOfBlockStateHash(),
                cutoverBlockStreamInfo.intermediatePreviousBlockRootHashes(),
                cutoverBlockStreamInfo.intermediateBlockRootsLeafCount());
        return true;
    }
}
