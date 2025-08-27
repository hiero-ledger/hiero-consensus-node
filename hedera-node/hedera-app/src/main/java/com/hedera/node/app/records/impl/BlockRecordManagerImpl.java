// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_KEY;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.stream.LinkedObjectStreamUtilities;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableSingletonStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * An implementation of {@link BlockRecordManager} primarily responsible for managing state ({@link RunningHashes} and
 * {@link BlockInfo}), and delegating to a {@link BlockRecordStreamProducer} for writing to the stream file, hashing,
 * and performing other duties, possibly on background threads. All APIs of {@link BlockRecordManager} can only be
 * called from the "handle" thread!
 */
@Singleton
public final class BlockRecordManagerImpl implements BlockRecordManager {
    private static final Logger logger = LogManager.getLogger(BlockRecordManagerImpl.class);

    /**
     * The number of blocks to keep multiplied by hash size. This is computed based on the
     * {@link BlockRecordStreamConfig#numOfBlockHashesInState()} setting multiplied by the size of each hash. This
     * setting is computed once at startup and used throughout.
     */
    private final int numBlockHashesToKeepBytes;
    /**
     * The number of seconds of consensus time in a block period, from configuration. This is computed based on the
     * {@link BlockRecordStreamConfig#logPeriod()} setting. This setting is computed once at startup and used
     * throughout.
     */
    private final long blockPeriodInSeconds;
    /**
     * The stream file producer we are using. This is set once during startup, and used throughout the execution of the
     * node. It would be nice to allow this to be a dynamic property, but it just isn't convenient to do so at this
     * time.
     */
    private final BlockRecordStreamProducer streamFileProducer;

    /**
     * A {@link BlockInfo} of the most recently completed block. This is actually available in state, but there
     * is no reason for us to read it from state every time we need it, we can just recompute and cache this every
     * time we finish a provisional block.
     */
    private BlockInfo lastBlockInfo;
    /**
     * True when we have completed event recovery. This is not yet implemented properly.
     */
    private boolean eventRecoveryCompleted;

    /**
     * Construct BlockRecordManager
     *
     * @param configProvider The configuration provider
     * @param state The current hedera state
     * @param streamFileProducer The stream file producer
     */
    @Inject
    public BlockRecordManagerImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final State state,
            @NonNull final BlockRecordStreamProducer streamFileProducer) {
        requireNonNull(state);
        this.streamFileProducer = requireNonNull(streamFileProducer);

        // FUTURE: check if we were started in event recover mode and if event recovery needs to be completed before we
        // write any new records to stream
        this.eventRecoveryCompleted = false;

        // Get static configuration that is assumed not to change while the node is running
        final var recordStreamConfig = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        this.blockPeriodInSeconds = recordStreamConfig.logPeriod();
        this.numBlockHashesToKeepBytes = recordStreamConfig.numOfBlockHashesInState() * HASH_SIZE;

        // Initialize the last block info and provisional block info.
        // NOTE: State migration happens BEFORE dagger initialization, and this object is managed by dagger. So we are
        // guaranteed that the state exists PRIOR to this call.
        final var states = state.getReadableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY);
        this.lastBlockInfo = blockInfoState.get();
        assert this.lastBlockInfo != null : "Cannot be null, because this state is created at genesis";

        // Initialize the stream file producer. NOTE, if the producer cannot be initialized, and a random exception is
        // thrown here, then startup of the node will fail. This is the intended behavior. We MUST be able to produce
        // record streams, or there really is no point to running the node!
        final var runningHashState = states.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_KEY);
        final var lastRunningHashes = runningHashState.get();
        assert lastRunningHashes != null : "Cannot be null, because this state is created at genesis";
        this.streamFileProducer.initRunningHash(lastRunningHashes);
    }

    // =================================================================================================================
    // AutoCloseable implementation

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            streamFileProducer.close();
        } catch (final Exception e) {
            // This is a fairly serious warning. This basically means we cannot guarantee that some records were
            // produced. However, since the {@link BlockRecordManager} is a singleton, this close method is only called
            // when the node is being shutdown anyway.
            logger.warn("Failed to close streamFileProducer properly", e);
        }
    }

    // =================================================================================================================
    // BlockRecordManager implementation

    @Override
    public boolean willOpenNewBlock(@NonNull final Instant consensusTime, @NonNull final State state) {
        if (EPOCH.equals(lastBlockInfo.firstConsTimeOfCurrentBlock())) {
            return true;
        }
        final var currentBlockPeriod = getBlockPeriod(lastBlockInfo.firstConsTimeOfCurrentBlock());
        final var newBlockPeriod = getBlockPeriod(consensusTime);
        if (newBlockPeriod > currentBlockPeriod) {
            return true;
        }
        final var platformState = state.getReadableStates(PlatformStateService.NAME)
                .<PlatformState>getSingleton(PLATFORM_STATE_KEY)
                .get();
        requireNonNull(platformState);
        return platformState.freezeTime() != null
                && platformState.freezeTimeOrThrow().equals(platformState.lastFrozenTime());
    }

    /**
     * {@inheritDoc}
     */
    public boolean startUserTransaction(@NonNull final Instant consensusTime, @NonNull final State state) {
        if (EPOCH.equals(lastBlockInfo.firstConsTimeOfCurrentBlock())) {
            // This is the first transaction of the first block, so set both the firstConsTimeOfCurrentBlock
            // and the current consensus time to now
            final var now = new Timestamp(consensusTime.getEpochSecond(), consensusTime.getNano());
            lastBlockInfo = lastBlockInfo
                    .copyBuilder()
                    .consTimeOfLastHandledTxn(now)
                    .firstConsTimeOfCurrentBlock(now)
                    .build();
            putLastBlockInfo(state);
            streamFileProducer.switchBlocks(-1, 0, consensusTime);
            return true;
        }

        // Check to see if we are at the boundary between blocks and should create a new one. Each block is covered
        // by some period. We'll compute the period of the current provisional block and the period covered by the
        // given consensus time, and if they are different, we'll close out the current block and start a new one.
        final var currentBlockPeriod = getBlockPeriod(lastBlockInfo.firstConsTimeOfCurrentBlock());
        final var newBlockPeriod = getBlockPeriod(consensusTime);

        final var platformState = state.getReadableStates(PlatformStateService.NAME)
                .<PlatformState>getSingleton(PLATFORM_STATE_KEY)
                .get();
        requireNonNull(platformState);
        // Also check to see if this is the first transaction we're handling after a freeze restart. If so, we also
        // start a new block.
        final var isFirstTransactionAfterFreezeRestart = platformState.freezeTime() != null
                && platformState.freezeTimeOrThrow().equals(platformState.lastFrozenTime());
        if (isFirstTransactionAfterFreezeRestart) {
            new WritablePlatformStateStore(state.getWritableStates(PlatformStateService.NAME)).setFreezeTime(null);
        }
        // Now we test if we need to start a new block. If so, create the new block
        if (newBlockPeriod > currentBlockPeriod || isFirstTransactionAfterFreezeRestart) {
            // Compute the state for the newly completed block. The `lastBlockHashBytes` is the running hash after
            // the last transaction
            final var lastBlockHashBytes = streamFileProducer.getRunningHash();
            final var justFinishedBlockNumber = lastBlockInfo.lastBlockNumber() + 1;
            lastBlockInfo =
                    infoOfJustFinished(lastBlockInfo, justFinishedBlockNumber, lastBlockHashBytes, consensusTime);

            // Update BlockInfo state
            putLastBlockInfo(state);

            // log end of block if needed
            if (logger.isDebugEnabled()) {
                logger.debug(
                        """
                                --- BLOCK UPDATE ---
                                  Finished: #{} (started @ {}) with hash {}
                                  Starting: #{} @ {}""",
                        justFinishedBlockNumber,
                        lastBlockInfo.firstConsTimeOfCurrentBlock(),
                        new Hash(lastBlockHashBytes, DigestType.SHA_384),
                        justFinishedBlockNumber + 1,
                        consensusTime);
            }

            switchBlocksAt(consensusTime);
            return true;
        }
        return false;
    }

    @Override
    public void markMigrationRecordsStreamed() {
        lastBlockInfo =
                lastBlockInfo.copyBuilder().migrationRecordsStreamed(true).build();
    }

    /**
     * We need this to preserve unit test expectations written that assumed a bug in the original implementation,
     * in which the first consensus time of the current block was not in state.
     *
     * @param consensusTime the consensus time at which to switch to the current block
     */
    @VisibleForTesting
    public void switchBlocksAt(@NonNull final Instant consensusTime) {
        streamFileProducer.switchBlocks(
                lastBlockInfo.lastBlockNumber(), lastBlockInfo.lastBlockNumber() + 1, consensusTime);
    }

    private void putLastBlockInfo(@NonNull final State state) {
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY);
        blockInfoState.put(lastBlockInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void endUserTransaction(
            @NonNull final Stream<SingleTransactionRecord> recordStreamItems, @NonNull final State state) {
        // check if we need to run event recovery before we can write any new records to stream
        if (!this.eventRecoveryCompleted) {
            // FUTURE create event recovery class and call it here. Should this be in startUserTransaction()?
            this.eventRecoveryCompleted = true;
        }
        // pass to record stream writer to handle
        streamFileProducer.writeRecordStreamItems(recordStreamItems);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void endRound(@NonNull final State state) {
        // We get the latest running hash from the StreamFileProducer blocking if needed for it to be computed.
        final var currentRunningHash = streamFileProducer.getRunningHash();
        // Update running hashes in state with the latest running hash and the previous 3 running hashes.
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var runningHashesState = states.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_KEY);
        final var existingRunningHashes = runningHashesState.get();
        assert existingRunningHashes != null : "This cannot be null because genesis migration sets it";
        final var runningHashes = new RunningHashes(
                currentRunningHash,
                existingRunningHashes.nMinus1RunningHash(),
                existingRunningHashes.nMinus2RunningHash(),
                existingRunningHashes.nMinus3RunningHash());
        runningHashesState.put(runningHashes);
        // Commit the changes to the merkle tree.
        ((WritableSingletonStateBase<RunningHashes>) runningHashesState).commit();
    }

    public long lastBlockNo() {
        return lastBlockInfo.lastBlockNumber();
    }

    public Instant firstConsTimeOfLastBlock() {
        return BlockRecordInfoUtils.firstConsTimeOfLastBlock(lastBlockInfo);
    }

    public Bytes getRunningHash() {
        return streamFileProducer.getRunningHash();
    }

    @Nullable
    public Bytes lastBlockHash() {
        return BlockRecordInfoUtils.lastBlockHash(lastBlockInfo);
    }

    // ========================================================================================================
    // Running Hash Getter Methods
    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes prngSeed() {
        return streamFileProducer.getNMinus3RunningHash();
    }

    // ========================================================================================================
    // BlockRecordInfo Implementation

    @Override
    public long blockNo() {
        return lastBlockInfo.lastBlockNumber() + 1;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Instant consTimeOfLastHandledTxn() {
        final var lastHandledTxn = lastBlockInfo.consTimeOfLastHandledTxn();
        return lastHandledTxn != null
                ? Instant.ofEpochSecond(lastHandledTxn.seconds(), lastHandledTxn.nanos())
                : Instant.EPOCH;
    }

    @Override
    public @NonNull Timestamp blockTimestamp() {
        return lastBlockInfo.firstConsTimeOfCurrentBlockOrThrow();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes blockHashByBlockNumber(final long blockNo) {
        return BlockRecordInfoUtils.blockHashByBlockNumber(lastBlockInfo, blockNo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastTopLevelTime(@NonNull final Instant consensusTime, @NonNull final State state) {
        final var now = asTimestamp(consensusTime);
        final var builder =
                this.lastBlockInfo.copyBuilder().consTimeOfLastHandledTxn(now).lastUsedConsTime(now);
        updateBlockInfo(builder.build(), state);
    }

    @Override
    public void setLastUsedConsensusTime(@NonNull Instant consensusTime, @NonNull State state) {
        requireNonNull(consensusTime);
        requireNonNull(state);
        final var newBlockInfo = new BlockInfo(
                lastBlockInfo.lastBlockNumber(),
                lastBlockInfo.firstConsTimeOfLastBlock(),
                lastBlockInfo.blockHashes(),
                lastBlockInfo.consTimeOfLastHandledTxn(),
                lastBlockInfo.migrationRecordsStreamed(),
                lastBlockInfo.firstConsTimeOfCurrentBlock(),
                asTimestamp(consensusTime),
                lastBlockInfo.lastIntervalProcessTime());
        updateBlockInfo(newBlockInfo, state);
    }

    @NonNull
    @Override
    public Instant lastUsedConsensusTime() {
        return lastBlockInfo.hasLastUsedConsTime() ? asInstant(lastBlockInfo.lastUsedConsTimeOrThrow()) : Instant.EPOCH;
    }

    @Override
    public void setLastIntervalProcessTime(@NonNull Instant lastIntervalProcessTime, @NonNull final State state) {
        requireNonNull(lastIntervalProcessTime);
        requireNonNull(state);
        final var newBlockInfo = lastBlockInfo
                .copyBuilder()
                .lastIntervalProcessTime(asTimestamp(lastIntervalProcessTime))
                .build();
        updateBlockInfo(newBlockInfo, state);
    }

    @NonNull
    @Override
    public Instant lastIntervalProcessTime() {
        return lastBlockInfo.hasLastIntervalProcessTime()
                ? asInstant(lastBlockInfo.lastIntervalProcessTimeOrThrow())
                : Instant.EPOCH;
    }

    /**
     * Check if the consensus time of the last handled transaction is the default value. This is
     * used to determine if migration records should be streamed
     *
     * @param blockInfo the block info object to test
     * @return true if the given block info has a last handled transaction time that is considered a
     * 'default' or 'unset' value, false otherwise.
     */
    public static boolean isDefaultConsTimeOfLastHandledTxn(@Nullable final BlockInfo blockInfo) {
        if (blockInfo == null || blockInfo.consTimeOfLastHandledTxn() == null) {
            return true;
        }

        // If there is a value, it is considered a 'default' value unless it is after Instant.EPOCH
        var inst = Instant.ofEpochSecond(
                blockInfo.consTimeOfLastHandledTxn().seconds(),
                blockInfo.consTimeOfLastHandledTxn().nanos());
        return !inst.isAfter(Instant.EPOCH);
    }

    // ========================================================================================================
    // Private Methods

    /**
     * Get the block period from consensus timestamp. Based on
     * {@link LinkedObjectStreamUtilities#getPeriod(Instant, long)} but updated to work on {@link Instant}.
     *
     * @param consensusTimestamp The consensus timestamp
     * @return The block period from epoch the consensus timestamp is in
     */
    private long getBlockPeriod(@Nullable final Instant consensusTimestamp) {
        if (consensusTimestamp == null) return 0;
        return consensusTimestamp.getEpochSecond() / blockPeriodInSeconds;
    }

    private long getBlockPeriod(@Nullable final Timestamp consensusTimestamp) {
        if (consensusTimestamp == null) return 0;
        return consensusTimestamp.seconds() / blockPeriodInSeconds;
    }

    /**
     * Create a new updated BlockInfo from existing BlockInfo and new block information. BlockInfo stores block hashes as a single
     * byte array, so we need to append or if full shift left and insert new block hash.
     *
     * @param lastBlockInfo The current block info
     * @param justFinishedBlockNumber The new block number
     * @param hashOfJustFinishedBlock The new block hash
     */
    private BlockInfo infoOfJustFinished(
            @NonNull final BlockInfo lastBlockInfo,
            final long justFinishedBlockNumber,
            @NonNull final Bytes hashOfJustFinishedBlock,
            @NonNull final Instant currentBlockFirstTransactionTime) {
        // compute new block hashes bytes
        final byte[] blockHashesBytes = lastBlockInfo.blockHashes().toByteArray();
        byte[] newBlockHashesBytes;
        if (blockHashesBytes.length < numBlockHashesToKeepBytes) {
            // append new hash bytes to end
            newBlockHashesBytes = new byte[blockHashesBytes.length + HASH_SIZE];
            System.arraycopy(blockHashesBytes, 0, newBlockHashesBytes, 0, blockHashesBytes.length);
            hashOfJustFinishedBlock.getBytes(0, newBlockHashesBytes, newBlockHashesBytes.length - HASH_SIZE, HASH_SIZE);
        } else {
            // shift bytes left by HASH_SIZE and then set new hash bytes to at end HASH_SIZE bytes
            newBlockHashesBytes = blockHashesBytes;
            System.arraycopy(
                    newBlockHashesBytes, HASH_SIZE, newBlockHashesBytes, 0, newBlockHashesBytes.length - HASH_SIZE);
            hashOfJustFinishedBlock.getBytes(0, newBlockHashesBytes, newBlockHashesBytes.length - HASH_SIZE, HASH_SIZE);
        }
        return new BlockInfo(
                justFinishedBlockNumber,
                lastBlockInfo.firstConsTimeOfCurrentBlock(),
                Bytes.wrap(newBlockHashesBytes),
                lastBlockInfo.consTimeOfLastHandledTxn(),
                lastBlockInfo.migrationRecordsStreamed(),
                new Timestamp(
                        currentBlockFirstTransactionTime.getEpochSecond(), currentBlockFirstTransactionTime.getNano()),
                lastBlockInfo.lastUsedConsTime(),
                lastBlockInfo.lastIntervalProcessTime());
    }

    /**
     * Updates the given state with the new block info and caches it in the {@code lastBlockInfo} field.
     * @param newBlockInfo the new block info to update
     * @param state the state to update with the new block info
     */
    private void updateBlockInfo(@NonNull final BlockInfo newBlockInfo, @NonNull final State state) {
        // Update the latest block info in state
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY);
        blockInfoState.put(newBlockInfo);
        // Commit the changes. We don't ever want to roll back when advancing the consensus clock
        ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
        // Cache the updated block info
        this.lastBlockInfo = newBlockInfo;
    }
}
