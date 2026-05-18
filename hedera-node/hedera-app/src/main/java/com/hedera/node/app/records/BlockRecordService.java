// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.records.schemas.V0560BlockRecordSchema;
import com.hedera.node.app.records.schemas.V0740BlockRecordSchema;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link Service} for managing the state of the running hashes and block information. Used by the
 * {@link BlockRecordManagerImpl}. This service is not exposed outside `hedera-app`.
 */
@Singleton
public final class BlockRecordService implements Service {
    private static final Logger log = LogManager.getLogger(BlockRecordService.class);

    /** The original hash, only used at genesis */
    private static final Bytes GENESIS_HASH = Bytes.wrap(new byte[48]);

    private static final int DEADLINE_BLOCK_NUMBER_BUFFER = 10;

    /** The name of this service */
    public static final String NAME = "BlockRecordService";

    /**
     * The epoch timestamp, a placeholder for time of an event that has never happened.
     */
    public static final Timestamp EPOCH = new Timestamp(0, 0);
    /**
     * The block info at genesis.
     */
    public static final BlockInfo GENESIS_BLOCK_INFO = BlockInfo.newBuilder()
            .lastBlockNumber(-1)
            .firstConsTimeOfLastBlock(EPOCH)
            .blockHashes(Bytes.EMPTY)
            .consTimeOfLastHandledTxn(EPOCH)
            .migrationRecordsStreamed(true)
            .firstConsTimeOfCurrentBlock(EPOCH)
            .lastUsedConsTime(EPOCH)
            .lastIntervalProcessTime(EPOCH)
            // Voting completion should default to a no-op (i.e. complete = true) except under explicit circumstances
            .votingComplete(true)
            .votingCompletionDeadlineBlockNumber(0)
            .build();
    /**
     * The running hashes at genesis.
     */
    public static final RunningHashes GENESIS_RUNNING_HASHES =
            RunningHashes.newBuilder().runningHash(GENESIS_HASH).build();

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490BlockRecordSchema());
        registry.register(new V0560BlockRecordSchema());
        registry.register(new V0740BlockRecordSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID).put(GENESIS_BLOCK_INFO);
        writableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID).put(GENESIS_RUNNING_HASHES);
        return true;
    }

    @Override
    public boolean doPostUpgradeSetup(
            @NonNull final WritableStates writableStates, @NonNull final PostUpgradeContext context) {
        requireNonNull(writableStates);
        requireNonNull(context);
        final var configuration = context.configuration();
        if (!configuration.getConfigData(BlockRecordStreamConfig.class).liveWritePrevWrappedRecordHashes()
                || configuration.getConfigData(BlockStreamJumpstartConfig.class).blockNum() <= 0
                || !writableStates.contains(BLOCKS_STATE_ID)) {
            return false;
        }

        final var blockInfoState = writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID);
        final var existingBlockInfo = blockInfoState.get();
        if (existingBlockInfo == null) {
            log.info("Skipping wrapped record voting initialization because BlockInfo singleton does not exist");
            return false;
        }
        if (wrappedRecordVotingAlreadyInitialized(existingBlockInfo)) {
            return false;
        }

        final long votingCompletionDeadlineBlockNumber =
                existingBlockInfo.lastBlockNumber() + DEADLINE_BLOCK_NUMBER_BUFFER;
        blockInfoState.put(existingBlockInfo
                .copyBuilder()
                .votingComplete(false)
                .votingCompletionDeadlineBlockNumber(votingCompletionDeadlineBlockNumber)
                .migrationRootHashVotes(List.of())
                .build());
        log.info("Initialized wrapped record voting singleton with deadline={}", votingCompletionDeadlineBlockNumber);
        return true;
    }

    private static boolean wrappedRecordVotingAlreadyInitialized(@NonNull final BlockInfo blockInfo) {
        return blockInfo.votingCompletionDeadlineBlockNumber() > 0
                || !blockInfo.migrationRootHashVotes().isEmpty()
                || !blockInfo.migrationWrappedHashes().isEmpty();
    }
}
