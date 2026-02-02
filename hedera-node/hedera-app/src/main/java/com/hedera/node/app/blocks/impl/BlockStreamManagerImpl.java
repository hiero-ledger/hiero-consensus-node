// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_STREAM_INFO;
import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_384;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.GENESIS_WORK;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.NONE;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.POST_UPGRADE_WORK;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.blockDirFor;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.cleanUpPendingBlock;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.loadContiguousPendingBlocks;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.quiescence.TctProbe.blockStreamInfoFrom;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static com.swirlds.platform.state.service.PlatformStateUtils.creationSemanticVersionOf;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.info.DiskStartupNetworks.InfoType;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.quiescence.TctProbe;
import com.hedera.node.app.records.impl.BlockRecordInfoUtils;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.DiskNetworkExport;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

@Singleton
public class BlockStreamManagerImpl implements BlockStreamManager {
    private static final Logger log = LogManager.getLogger(BlockStreamManagerImpl.class);

    private final int roundsPerBlock;
    private final Duration blockPeriod;
    private final int hashCombineBatchSize;
    private final BlockHashSigner blockHashSigner;
    private final SemanticVersion version;
    private final SemanticVersion hapiVersion;
    private final ForkJoinPool executor;
    private final String diskNetworkExportFile;
    private final DiskNetworkExport diskNetworkExport;
    private final ConfigProvider configProvider;
    private final Supplier<BlockItemWriter> writerSupplier;
    private final BoundaryStateChangeListener boundaryStateChangeListener;

    private final Lifecycle lifecycle;
    private final BlockHashManager blockHashManager;
    private final RunningHashManager runningHashManager;
    private final Platform platform;
    private final QuiescenceController quiescenceController;
    private final QuiescedHeartbeat quiescedHeartbeat;

    // The status of pending work
    private PendingWork pendingWork = NONE;
    // The last time at which interval-based processing was done
    private Instant lastIntervalProcessTime = Instant.EPOCH;
    // The last consensus time for a top-level transaction; since only top-level transactions
    // can trigger stake period side effects, it is important to distinguish this from the
    // last-used consensus time for _any_ transaction (which might be children)
    private Instant lastTopLevelTime = Instant.EPOCH;
    // All this state is scoped to producing the current block
    private long blockNumber;
    private int eventIndex = 0;
    private final Map<Hash, Integer> eventIndexInBlock = new ConcurrentHashMap<>();
    private final AtomicReference<QuiescenceCommand> lastQuiescenceCommand = new AtomicReference<>();
    // The last non-empty (i.e., not skipped) round number that will eventually get a start-of-state hash
    private Bytes lastBlockHash;
    private long lastRoundOfPrevBlock;
    // A block's starting timestamp is defined as the consensus timestamp of the round's first transaction
    private Timestamp blockTimestamp;
    private Instant consensusTimeCurrentRound;
    private Timestamp lastUsedTime;
    private BlockItemWriter writer;

    // Block merkle subtrees and leaves
    private IncrementalStreamingHasher previousBlockHashes;
    private IncrementalStreamingHasher consensusHeaderHasher;
    private IncrementalStreamingHasher inputTreeHasher;
    private IncrementalStreamingHasher outputTreeHasher;
    private IncrementalStreamingHasher stateChangesHasher;
    private IncrementalStreamingHasher traceDataHasher;

    private BlockStreamManagerTask worker;
    private final boolean hintsEnabled;
    private final boolean quiescenceEnabled;

    /**
     * Blocks awaiting proof via ledger signature on their block hash (or a subsequent block hash).
     */
    private final Queue<PendingBlock> pendingBlocks = new ConcurrentLinkedQueue<>();
    /**
     * Futures that resolve when the end-of-round state hash is available for a given round number.
     */
    private final Map<Long, CompletableFuture<Bytes>> endRoundStateHashes = new ConcurrentHashMap<>();

    /**
     * If not null, a future to complete when the block manager's fatal shutdown process is done.
     */
    @Nullable
    private volatile CompletableFuture<Void> fatalShutdownFuture = null;

    /**
     * False until the node has tried to recover any blocks pending TSS signature still on disk.
     */
    private boolean hasCheckedForPendingBlocks = false;
    /**
     * The counter for the number of blocks closed with indirect proofs.
     */
    private final Counter indirectProofCounter;

    @Inject
    public BlockStreamManagerImpl(
            @NonNull final BlockHashSigner blockHashSigner,
            @NonNull final Supplier<BlockItemWriter> writerSupplier,
            @NonNull final ExecutorService executor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final Platform platform,
            @NonNull final QuiescenceController quiescenceController,
            @NonNull final InitialStateHash initialStateHash,
            @NonNull final SemanticVersion version,
            @NonNull final Lifecycle lifecycle,
            @NonNull final QuiescedHeartbeat quiescedHeartbeat,
            @NonNull final Metrics metrics) {
        this.blockHashSigner = requireNonNull(blockHashSigner);
        this.platform = requireNonNull(platform);
        this.quiescenceController = requireNonNull(quiescenceController);
        this.version = requireNonNull(version);
        this.writerSupplier = requireNonNull(writerSupplier);
        this.executor = (ForkJoinPool) requireNonNull(executor);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        this.lifecycle = requireNonNull(lifecycle);
        this.configProvider = requireNonNull(configProvider);
        this.quiescedHeartbeat = requireNonNull(quiescedHeartbeat);
        final var config = configProvider.getConfiguration();
        this.hintsEnabled = config.getConfigData(TssConfig.class).hintsEnabled();
        this.quiescenceEnabled = config.getConfigData(QuiescenceConfig.class).enabled();
        this.hapiVersion = hapiVersionFrom(config);
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        this.roundsPerBlock = blockStreamConfig.roundsPerBlock();
        this.blockPeriod = blockStreamConfig.blockPeriod();
        this.hashCombineBatchSize = blockStreamConfig.hashCombineBatchSize();
        final var networkAdminConfig = config.getConfigData(NetworkAdminConfig.class);
        this.diskNetworkExport = networkAdminConfig.diskNetworkExport();
        this.diskNetworkExportFile = networkAdminConfig.diskNetworkExportFile();
        this.blockHashManager = new BlockHashManager(config);
        this.runningHashManager = new RunningHashManager();
        this.lastRoundOfPrevBlock = initialStateHash.roundNum();
        final var hashFuture = initialStateHash.hashFuture();
        endRoundStateHashes.put(lastRoundOfPrevBlock, hashFuture);
        indirectProofCounter = requireNonNull(metrics)
                .getOrCreate(new Counter.Config("block", "numIndirectProofs")
                        .withDescription("Number of blocks closed with indirect proofs"));
        if (!quiescenceEnabled) {
            log.info("Quiescence is disabled");
            quiescedHeartbeat.shutdown();
        }
        log.info(
                "Initialized BlockStreamManager from round {} with end-of-round state hash {}",
                lastRoundOfPrevBlock,
                hashFuture.isDone() ? hashFuture.join().toHex() : "<PENDING>");
    }

    @Override
    public boolean hasLedgerId() {
        return blockHashSigner.isReady();
    }

    @Override
    public void init(@NonNull final State state, @Nullable final Bytes lastBlockHash) {
        final Bytes effectiveLastBlockHash;
        if (lastBlockHash != null) {
            effectiveLastBlockHash = lastBlockHash;
            // (FUTURE) Adjust for non-genesis case of block stream cutover
            this.previousBlockHashes =
                    new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), new ArrayList<>(), 0);
        } else {
            final var blockStreamInfo = state.getReadableStates(BlockStreamService.NAME)
                    .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                    .get();
            requireNonNull(blockStreamInfo);

            // Most of the ingredients in the block hash are directly in the BlockStreamInfo
            // Branch 1: lastBlockHash
            final var prevBlockHash = blockStreamInfo.blockNumber() <= 0L
                    ? ZERO_BLOCK_HASH
                    : BlockRecordInfoUtils.blockHashByBlockNumber(
                            blockStreamInfo.trailingBlockHashes(),
                            blockStreamInfo.blockNumber() - 1,
                            blockStreamInfo.blockNumber() - 1);
            // Branch 2
            final var prevBlocksIntermediateHashes = blockStreamInfo.intermediatePreviousBlockRootHashes().stream()
                    .map(Bytes::toByteArray)
                    .toList();
            this.previousBlockHashes = new IncrementalStreamingHasher(
                    sha384DigestOrThrow(),
                    prevBlocksIntermediateHashes,
                    blockStreamInfo.intermediateBlockRootsLeafCount());
            final var allPrevBlocksHash = Bytes.wrap(previousBlockHashes.computeRootHash());

            // Branch 3: Retrieve the previous block's starting state hash (not done right here, just part of the
            // calculated
            // last block hash below)

            // We have to calculate the final hash of the previous block's state changes subtree because only the
            // penultimate state hash is in the block stream info object (constructed from numPrecedingStateChangesItems
            // and
            // rightmostPrecedingStateChangesTreeHashes)
            final var initialStateChangesHasher = new IncrementalStreamingHasher(
                    sha384DigestOrThrow(),
                    blockStreamInfo.rightmostPrecedingStateChangesTreeHashes().stream()
                            .map(Bytes::toByteArray)
                            .toList(),
                    blockStreamInfo.numPrecedingStateChangesItems());

            // Reconstruct the final state change block item that would have been emitted by the previous block
            final var lastBlockFinalStateChange = StateChange.newBuilder()
                    .stateId(STATE_ID_BLOCK_STREAM_INFO.protoOrdinal())
                    .singletonUpdate(SingletonUpdateChange.newBuilder()
                            .blockStreamInfoValue(blockStreamInfo)
                            .build())
                    .build();
            final var lastStateChanges = BlockItem.newBuilder()
                    // The final state changes block item for the last block uses blockEndTime, which has to be the last
                    // state change time
                    .stateChanges(new StateChanges(blockStreamInfo.blockEndTime(), List.of(lastBlockFinalStateChange)))
                    .build();
            initialStateChangesHasher.addLeaf(
                    BlockItem.PROTOBUF.toBytes(lastStateChanges).toByteArray());
            final var lastBlockFinalStateChangesHash = Bytes.wrap(initialStateChangesHasher.computeRootHash());
            effectiveLastBlockHash = BlockStreamManagerImpl.combine(
                            prevBlockHash,
                            allPrevBlocksHash,
                            blockStreamInfo.startOfBlockStateHash(),
                            blockStreamInfo.consensusHeaderRootHash(),
                            blockStreamInfo.inputTreeRootHash(),
                            blockStreamInfo.outputItemRootHash(),
                            lastBlockFinalStateChangesHash,
                            blockStreamInfo.traceDataRootHash(),
                            blockStreamInfo.blockTimeOrThrow())
                    .blockRootHash();
        }
        this.lastBlockHash = effectiveLastBlockHash;
        if (!Objects.equals(effectiveLastBlockHash, ZERO_BLOCK_HASH)) {
            this.previousBlockHashes.addLeaf(effectiveLastBlockHash.toByteArray());
        }
    }

    @Override
    public void startRound(@NonNull final Round round, @NonNull final MerkleNodeState state) {
        if (lastBlockHash == null) {
            throw new IllegalStateException("Last block hash must be initialized before starting a round");
        }
        if (fatalShutdownFuture != null) {
            log.fatal("Ignoring round {} after fatal shutdown request", round.getRoundNum());
            return;
        }

        // In case we hash this round, include a future for the end-of-round state hash
        endRoundStateHashes.put(round.getRoundNum(), new CompletableFuture<>());

        // Writer will be null when beginning a new block
        if (writer == null) {
            writer = writerSupplier.get();
            blockTimestamp = asTimestamp(firstConsensusTimestampOf(round));
            lastUsedTime = asTimestamp(round.getConsensusTimestamp());

            final var blockStreamInfo = blockStreamInfoFrom(state, ZERO_BLOCK_HASH.equals(lastBlockHash));
            pendingWork = classifyPendingWork(blockStreamInfo, version);
            lastTopLevelTime = asInstant(blockStreamInfo.lastHandleTimeOrElse(EPOCH));
            lastIntervalProcessTime = asInstant(blockStreamInfo.lastIntervalProcessTimeOrElse(EPOCH));
            blockHashManager.startBlock(blockStreamInfo, lastBlockHash);
            runningHashManager.startBlock(blockStreamInfo);

            lifecycle.onOpenBlock(state);

            resetSubtrees();

            blockNumber = blockStreamInfo.blockNumber() + 1;
            if (hintsEnabled && !hasCheckedForPendingBlocks) {
                final var platformState = state.getReadableStates(PlatformStateService.NAME)
                        .<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID)
                        .get();
                if (platformState != null && platformState.hasLastFrozenTime()) {
                    recoverPendingBlocks();
                }
                hasCheckedForPendingBlocks = true;
            }
            // No-op if quiescence is disabled
            quiescenceController.startingBlock(blockNumber);
            worker = new BlockStreamManagerTask();
            final var header = BlockHeader.newBuilder()
                    .number(blockNumber)
                    .hashAlgorithm(SHA2_384)
                    .softwareVersion(creationSemanticVersionOf(state))
                    .blockTimestamp(blockTimestamp)
                    .hapiProtoVersion(hapiVersion);
            worker.addItem(BlockItem.newBuilder().blockHeader(header).build());
        }
        consensusTimeCurrentRound = round.getConsensusTimestamp();
    }

    /**
     * Initializes the block stream manager after a restart or during reconnect with the hash of the last block
     * incorporated in the state used in the restart or reconnect. (At genesis, this hash should be the
     * {@link #ZERO_BLOCK_HASH}.)
     *
     * @param blockHash the hash of the last block
     */
    @VisibleForTesting
    void initLastBlockHash(@NonNull final Bytes blockHash) {
        lastBlockHash = requireNonNull(blockHash);
    }

    /**
     * Recovers the contents and proof context of any pending blocks from disk.
     */
    private void recoverPendingBlocks() {
        final var config = configProvider.getConfiguration();
        final var blockDirPath = blockDirFor(config);
        log.info(
                "Attempting to recover any pending blocks contiguous to #{} still on disk @ {}",
                blockNumber,
                blockDirPath.toAbsolutePath());
        try {
            final var onDiskPendingBlocks = loadContiguousPendingBlocks(
                    blockDirPath, blockNumber, maxReadDepth(config), maxReadBytesSize(config));
            if (onDiskPendingBlocks.isEmpty()) {
                log.info("No contiguous pending blocks found for block #{}", blockNumber);
                final var pendingWriter = writerSupplier.get();
                pendingWriter.jumpToBlockAfterFreeze(blockNumber);
                return;
            }

            for (int i = 0; i < onDiskPendingBlocks.size(); i++) {
                var block = onDiskPendingBlocks.get(i);
                try {
                    final var pendingWriter = writerSupplier.get();
                    if (i == 0) { // jump to the first pending block
                        pendingWriter.jumpToBlockAfterFreeze(
                                onDiskPendingBlocks.getFirst().number());
                    }

                    pendingWriter.openBlock(block.number());
                    block.items()
                            .forEach(
                                    item -> pendingWriter.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item)));
                    pendingBlocks.add(new PendingBlock(
                            block.number(),
                            block.contentsPath(),
                            block.blockHash(),
                            block.pendingProof().previousBlockHash(),
                            block.proofBuilder(),
                            pendingWriter,
                            block.pendingProof().blockTimestamp(),
                            block.siblingHashesIfUseful()));
                    log.info("Recovered pending block #{}", block.number());
                } catch (Exception e) {
                    log.warn("Failed to recover pending block #{}", block.number(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load pending blocks", e);
        }
    }

    @Override
    public void confirmPendingWorkFinished() {
        if (pendingWork == NONE) {
            // Should never happen but throwing IllegalStateException might make the situation even worse, so just log
            log.error("HandleWorkflow confirmed finished work but none was pending");
        }
        pendingWork = NONE;
    }

    @Override
    public @NonNull PendingWork pendingWork() {
        return pendingWork;
    }

    @Override
    public @NonNull Instant lastIntervalProcessTime() {
        return lastIntervalProcessTime;
    }

    @Override
    public void setLastIntervalProcessTime(@NonNull final Instant lastIntervalProcessTime) {
        this.lastIntervalProcessTime = requireNonNull(lastIntervalProcessTime);
    }

    @Override
    public @NonNull final Instant lastTopLevelConsensusTime() {
        return lastTopLevelTime;
    }

    @Override
    public void setLastTopLevelTime(@NonNull final Instant lastTopLevelTime) {
        this.lastTopLevelTime = requireNonNull(lastTopLevelTime);
    }

    @Override
    public @NonNull Instant lastUsedConsensusTime() {
        return asInstant(lastUsedTime);
    }

    @Override
    public boolean endRound(@NonNull final MerkleNodeState state, final long roundNum) {
        final var storeFactory = new ReadableStoreFactoryImpl(state);
        final var platformStateStore = storeFactory.readableStore(ReadablePlatformStateStore.class);
        final long freezeRoundNumber = platformStateStore.getLatestFreezeRound();
        final boolean closesBlock = shouldCloseBlock(roundNum, freezeRoundNumber);
        if (closesBlock) {
            lifecycle.onCloseBlock(state);
            // No-op if quiescence is disabled
            quiescenceController.finishHandlingInProgressBlock();
            state.commitSingletons();
            // Flush all boundary state changes besides the BlockStreamInfo
            worker.addItem(flushChangesFromListener(boundaryStateChangeListener));
            worker.sync();

            // This block's starting state hash is the end state hash of the last non-empty round
            final var blockStartStateHash = requireNonNull(endRoundStateHashes.get(lastRoundOfPrevBlock))
                    .join();
            // Now clean up hash futures for rounds before the one closing this block
            for (long i = lastRoundOfPrevBlock; i < roundNum; i++) {
                endRoundStateHashes.remove(i);
            }
            // And update the last non-empty round number to this round
            lastRoundOfPrevBlock = roundNum;

            // Branch 1: lastBlockHash
            // Branch 2: previous block hashes root hash
            // Branch 3: blockStartStateHash
            // Branch 4 final hash:
            final var consensusHeaderHash = Bytes.wrap(consensusHeaderHasher.computeRootHash());
            // Branch 5 final hash:
            final var inputsHash = Bytes.wrap(inputTreeHasher.computeRootHash());
            // Branch 6 final hash:
            final var outputsHash = Bytes.wrap(outputTreeHasher.computeRootHash());
            // Branch 7 (penultimate status only because there will be one more state change when the block stream info
            // object is stored)
            final var interimStateChanges = stateChangesHasher.intermediateHashingState();
            final var interimStateChangeLeaves = stateChangesHasher.leafCount();
            // Branch 8 final hash:
            final var traceDataHash = Bytes.wrap(traceDataHasher.computeRootHash());

            // Put this block hash context in state via the block stream info
            final var writableState = state.getWritableStates(BlockStreamService.NAME);
            final var blockStreamInfoState = writableState.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
            final var newBlockStreamInfo = new BlockStreamInfo(
                    blockNumber,
                    blockTimestamp(),
                    runningHashManager.latestHashes(),
                    blockHashManager.blockHashes(),
                    inputsHash,
                    blockStartStateHash,
                    interimStateChangeLeaves,
                    interimStateChanges,
                    lastUsedTime,
                    pendingWork != POST_UPGRADE_WORK,
                    version,
                    asTimestamp(lastIntervalProcessTime),
                    asTimestamp(lastTopLevelTime),
                    consensusHeaderHash,
                    outputsHash,
                    traceDataHash,
                    previousBlockHashes.intermediateHashingState(),
                    previousBlockHashes.leafCount());
            blockStreamInfoState.put(newBlockStreamInfo);
            ((CommittableWritableStates) writableState).commit();

            // Produce one more state change item (i.e. putting the block stream info just constructed into state)
            worker.addItem(flushChangesFromListener(boundaryStateChangeListener));
            worker.sync();

            final var stateChangesHash = Bytes.wrap(stateChangesHasher.computeRootHash());

            final var prevBlockRootsHash = Bytes.wrap(previousBlockHashes.computeRootHash());
            final var rootAndSiblingHashes = combine(
                    lastBlockHash,
                    prevBlockRootsHash,
                    blockStartStateHash,
                    consensusHeaderHash,
                    inputsHash,
                    outputsHash,
                    stateChangesHash,
                    traceDataHash,
                    newBlockStreamInfo.blockTime());
            final var finalBlockRootHash = rootAndSiblingHashes.blockRootHash();

            // Create BlockFooter with the three essential hashes:
            final var blockFooter = com.hedera.hapi.block.stream.output.BlockFooter.newBuilder()
                    // 1. previousBlockRootHash - Root hash of the previous block (N-1)
                    .previousBlockRootHash(lastBlockHash)
                    // 2. rootHashOfAllBlockHashesTree - Root streaming tree of all block hashes 0..N-1
                    .rootHashOfAllBlockHashesTree(prevBlockRootsHash)
                    // 3. startOfBlockStateRootHash - State hash at the beginning of current block
                    .startOfBlockStateRootHash(blockStartStateHash)
                    .build();

            // Write BlockFooter to block stream (last item before BlockProof)
            final var footerItem =
                    BlockItem.newBuilder().blockFooter(blockFooter).build();
            worker.addItem(footerItem);
            worker.sync();

            // Create a pending block, waiting to be signed
            final var blockProofBuilder = BlockProof.newBuilder().block(blockNumber);
            pendingBlocks.add(new PendingBlock(
                    blockNumber,
                    null,
                    finalBlockRootHash,
                    lastBlockHash,
                    blockProofBuilder,
                    writer,
                    blockTimestamp,
                    rootAndSiblingHashes.siblingHashes()));

            // Update in-memory state to prepare for the next block
            lastBlockHash = finalBlockRootHash;
            previousBlockHashes.addLeaf(lastBlockHash.toByteArray());
            writer = null;

            // Special case when signing with hinTS and this is the freeze round; we have to wait
            // until after restart to gossip partial signatures and sign any pending blocks
            if (hintsEnabled && roundNum == freezeRoundNumber) {
                // In case the id of the next hinTS construction changed since a block ended
                pendingBlocks.forEach(block -> {
                    final var pendingProof = block.asPendingProof();
                    block.writer().flushPendingBlock(pendingProof);
                });
            } else {
                final var attempt = blockHashSigner.sign(finalBlockRootHash);
                attempt.signatureFuture().thenAcceptAsync(signature -> {
                    if (signature == null || Objects.equals(signature, Bytes.EMPTY)) {
                        log.debug(
                                "Signature future completed with empty signature for block num {}, final block hash {}",
                                blockNumber,
                                finalBlockRootHash);
                    } else {
                        finishProofWithSignature(
                                finalBlockRootHash, signature, attempt.verificationKey(), attempt.chainOfTrustProof());
                    }
                    if (quiescenceEnabled) {
                        final var lastCommand = lastQuiescenceCommand.get();
                        final var commandNow = quiescenceController.getQuiescenceStatus();
                        if (commandNow != lastCommand && lastQuiescenceCommand.compareAndSet(lastCommand, commandNow)) {
                            log.info("Updating quiescence command from {} to {}", lastCommand, commandNow);
                            platform.quiescenceCommand(commandNow);
                            if (commandNow == QUIESCE) {
                                final var config = configProvider.getConfiguration();
                                final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
                                quiescedHeartbeat.start(
                                        blockStreamConfig.quiescedHeartbeatInterval(),
                                        new TctProbe(
                                                blockStreamConfig.maxConsecutiveScheduleSecondsToProbe(),
                                                config.getConfigData(StakingConfig.class)
                                                        .periodMins(),
                                                state));
                            }
                        }
                    }
                });
            }

            final var exportNetworkToDisk =
                    switch (diskNetworkExport) {
                        case NEVER -> false;
                        case EVERY_BLOCK -> true;
                        case ONLY_FREEZE_BLOCK -> roundNum == freezeRoundNumber;
                    };
            if (exportNetworkToDisk) {
                final var exportPath = Paths.get(diskNetworkExportFile);
                log.info(
                        "Writing network info to disk @ {} (REASON = {})",
                        exportPath.toAbsolutePath(),
                        diskNetworkExport);
                DiskStartupNetworks.writeNetworkInfo(state, exportPath, EnumSet.allOf(InfoType.class));
            }

            // Clear the eventIndexInBlock map for the next block
            eventIndexInBlock.clear();
            eventIndex = 0;
        }
        if (fatalShutdownFuture != null) {
            pendingBlocks.forEach(block -> log.fatal("Skipping incomplete block proof for block {}", block.number()));
            if (writer != null) {
                log.fatal("Prematurely closing block {}", blockNumber);
                writer.closeCompleteBlock();
                writer = null;
            }
            requireNonNull(fatalShutdownFuture).complete(null);
        }
        return closesBlock;
    }

    @Override
    public void writeItem(@NonNull final BlockItem item) {
        lastUsedTime = switch (item.item().kind()) {
            case STATE_CHANGES -> item.stateChangesOrThrow().consensusTimestampOrThrow();
            case TRANSACTION_RESULT -> item.transactionResultOrThrow().consensusTimestampOrThrow();
            default -> lastUsedTime;
        };
        worker.addItem(item);
    }

    @Override
    public void writeItem(@NonNull final Function<Timestamp, BlockItem> itemSpec) {
        requireNonNull(itemSpec);
        writeItem(itemSpec.apply(lastUsedTime));
    }

    @Override
    public @Nullable Bytes prngSeed() {
        // Incorporate all pending results before returning the seed to guarantee
        // no two consecutive transactions ever get the same seed
        worker.sync();
        final var seed = runningHashManager.nMinus3Hash;
        return seed == null ? null : Bytes.wrap(runningHashManager.nMinus3Hash);
    }

    @Override
    public long blockNo() {
        return blockNumber;
    }

    @Override
    public @NonNull Timestamp blockTimestamp() {
        return requireNonNull(blockTimestamp);
    }

    @Override
    public @Nullable Bytes blockHashByBlockNumber(final long blockNo) {
        return blockHashManager.hashOfBlock(blockNo);
    }

    /**
     * If still pending, finishes the block proof for the block with the given hash using the given direct signature.
     * <p>
     * Synchronized to ensure that block proofs are always written in order, even in edge cases where multiple
     * pending block proofs become available at the same time.
     * <p>
     * <b>Note: It's critical to refrain from changing the signing algorithm while the chain of trust verification
     * is disabled!</b> See GitHub issue #22676 for more details.
     *
     * @param blockHash the block hash of the latest signed block. May be for a block later than the one being proven.
     * @param blockSignature the signature to use in the block proof
     * @param verificationKey if hinTS is enabled, the verification key to use in the block proof
     * @param chainOfTrustProof if history proofs are enabled, the chain of trust proof to use in the block proof
     */
    private synchronized void finishProofWithSignature(
            @NonNull final Bytes blockHash,
            @NonNull final Bytes blockSignature,
            @SuppressWarnings("unused") @Nullable final Bytes verificationKey,
            @SuppressWarnings("unused") @Nullable final ChainOfTrustProof chainOfTrustProof) {
        // Find the block whose hash is the signed message
        PendingBlock signedBlock = null;
        for (final var block : pendingBlocks) {
            if (block.blockHash().equals(blockHash)) {
                signedBlock = block;
                break;
            }
        }
        if (signedBlock == null) {
            log.debug("Ignoring signature on already proven block hash '{}'", blockHash);
            return;
        }
        final long blockNumber = signedBlock.number();

        final Bytes effectiveSignature;
        if (verificationKey != null && chainOfTrustProof != null) {
            if (chainOfTrustProof.hasWrapsProof()) {
                effectiveSignature =
                        verificationKey.append(blockSignature).append(chainOfTrustProof.wrapsProofOrThrow());
            } else {
                effectiveSignature = verificationKey
                        .append(blockSignature)
                        .append(chainOfTrustProof
                                .aggregatedNodeSignaturesOrThrow()
                                .aggregatedSignature());
            }
        } else if (verificationKey != null) {
            effectiveSignature = verificationKey.append(blockSignature);
        } else {
            effectiveSignature = blockSignature;
        }
        // Write proofs for all pending blocks up to and including the signed block number
        final var latestSignedBlockProof = TssSignedBlockProof.newBuilder()
                .blockSignature(effectiveSignature)
                .build();
        while (!pendingBlocks.isEmpty() && pendingBlocks.peek().number() <= blockNumber) {
            final var currentPendingBlock = pendingBlocks.poll();
            final BlockProof.Builder proof;
            if (currentPendingBlock.number() == blockNumber) {
                // Block signatures on the current block will always produce a TssSignedBlockProof
                proof = currentPendingBlock.proofBuilder().signedBlockProof(latestSignedBlockProof);
            } else {
                // This is a pending block whose block number precedes the signed block number, so we construct an
                // indirect state proof

                if (configProvider
                        .getConfiguration()
                        .getConfigData(BlockStreamConfig.class)
                        .enableStateProofs()) {
                    final var stateProof = BlockStateProofGenerator.generateStateProof(
                            currentPendingBlock,
                            blockNumber,
                            effectiveSignature,
                            signedBlock.blockTimestamp(),
                            // Pass the remaining pending blocks, but don't remove them from the queue
                            pendingBlocks.stream());
                    proof = currentPendingBlock.proofBuilder().blockStateProof(stateProof);

                    if (log.isDebugEnabled()) {
                        logStateProof(effectiveSignature, currentPendingBlock, blockNumber, stateProof);
                    }
                } else {
                    // (FUTURE) Once state proofs are enabled, this placeholder proof can be removed
                    proof = currentPendingBlock
                            .proofBuilder()
                            .blockStateProof(StateProof.newBuilder()
                                    .paths(MerklePath.newBuilder().build())
                                    .signedBlockProof(latestSignedBlockProof)
                                    .build());
                }

                // Update the metrics
                indirectProofCounter.increment();
            }

            // (FUTURE: GH issue #22676) Re-enable setting the verification key and chain of trust proof
            // once the API is finalized
            //            if (verificationKey != null) {
            //                proof.verificationKey(verificationKey);
            //                if (chainOfTrustProof != null) {
            //                    proof.verificationKeyProof(chainOfTrustProof);
            //                }
            //            }
            final var proofItem = BlockItem.newBuilder().blockProof(proof).build();
            currentPendingBlock.writer().writePbjItemAndBytes(proofItem, BlockItem.PROTOBUF.toBytes(proofItem));
            currentPendingBlock.writer().closeCompleteBlock();
            // Only report signatures to the quiescence controller if they were created in-memory first
            if (quiescenceEnabled && currentPendingBlock.contentsPath() == null) {
                quiescenceController.blockFullySigned(currentPendingBlock.number());
            }
            if (currentPendingBlock.contentsPath() != null) {
                cleanUpPendingBlock(currentPendingBlock.contentsPath());
            }
        }
    }

    /**
     * Classifies the type of work pending, if any, given the block stream info from state and the current
     * software version.
     *
     * @param blockStreamInfo the block stream info
     * @param version the version
     * @return the type of pending work given the block stream info and version
     */
    @VisibleForTesting
    static PendingWork classifyPendingWork(
            @NonNull final BlockStreamInfo blockStreamInfo, @NonNull final SemanticVersion version) {
        requireNonNull(version);
        requireNonNull(blockStreamInfo);
        if (EPOCH.equals(blockStreamInfo.lastHandleTimeOrElse(EPOCH))) {
            return GENESIS_WORK;
        } else if (impliesPostUpgradeWorkPending(blockStreamInfo, version)) {
            return POST_UPGRADE_WORK;
        } else {
            return NONE;
        }
    }

    private static boolean impliesPostUpgradeWorkPending(
            @NonNull final BlockStreamInfo blockStreamInfo, @NonNull final SemanticVersion version) {
        return !version.equals(blockStreamInfo.creationSoftwareVersion()) || !blockStreamInfo.postUpgradeWorkDone();
    }

    private boolean shouldCloseBlock(final long roundNumber, final long freezeRoundNumber) {
        if (fatalShutdownFuture != null) {
            return true;
        }
        // We need the signer to be ready
        if (!blockHashSigner.isReady()) {
            return false;
        }

        // During freeze round, we should close the block regardless of other conditions
        if (roundNumber == freezeRoundNumber || roundNumber == 1) {
            return true;
        }

        // If blockPeriod is 0, use roundsPerBlock
        if (blockPeriod.isZero()) {
            return roundNumber % roundsPerBlock == 0;
        }

        // For time-based blocks, check if enough consensus time has elapsed
        final var elapsed = Duration.between(asInstant(blockTimestamp), consensusTimeCurrentRound);
        return elapsed.compareTo(blockPeriod) >= 0;
    }

    private static void logStateProof(
            final @NonNull Bytes blockSignature,
            final PendingBlock currentPendingBlock,
            final long blockNumber,
            final StateProof stateProof) {
        final var tag = "Finishing proof (b=%s):".formatted(currentPendingBlock.number());
        log.debug("{} State proof inputs:", tag);
        log.debug("{}   - currentPendingBlock={}", tag, currentPendingBlock);
        log.debug("{}   - blockNumber={}", tag, blockNumber);
        log.debug("{}   - blockSignature={}", tag, blockSignature);
        log.debug("{}   - pending block siblings:", tag);
        final var pendingSibHashes = currentPendingBlock.siblingHashes();
        for (int i = 0; i < pendingSibHashes.length; i++) {
            final var sibling = pendingSibHashes[i];
            log.debug("{}     ||-- ({}) isFirst={}, siblingHash={}", i, tag, sibling.isFirst(), sibling.siblingHash());
        }

        log.debug("{} Generated state proof: {}", tag, stateProof);
    }

    class BlockStreamManagerTask {

        SequentialTask prevTask;
        SequentialTask currentTask;

        BlockStreamManagerTask() {
            prevTask = null;
            currentTask = new SequentialTask();
            currentTask.send();
        }

        void addItem(BlockItem item) {
            new ParallelTask(item, currentTask).send();
            SequentialTask nextTask = new SequentialTask();
            currentTask.send(nextTask);
            prevTask = currentTask;
            currentTask = nextTask;
        }

        void sync() {
            if (prevTask != null) {
                prevTask.join();
            }
        }
    }

    class ParallelTask extends AbstractTask {

        BlockItem item;
        SequentialTask out;

        ParallelTask(BlockItem item, SequentialTask out) {
            super(executor, 1);
            this.item = item;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            try {
                final byte[] bytes = BlockItem.PROTOBUF.toBytes(item).toByteArray();
                out.send(item, bytes);
                return true;
            } catch (Exception e) {
                log.error("{} - error hashing item {}", ALERT_MESSAGE, item, e);
                return false;
            }
        }
    }

    class SequentialTask extends AbstractTask {

        SequentialTask next;
        BlockItem item;
        byte[] serialized;

        SequentialTask() {
            super(executor, 3);
        }

        @Override
        protected boolean onExecute() {
            final var kind = item.item().kind();
            switch (kind) {
                case ROUND_HEADER, EVENT_HEADER -> consensusHeaderHasher.addLeaf(serialized);
                case SIGNED_TRANSACTION -> inputTreeHasher.addLeaf(serialized);
                case TRANSACTION_RESULT -> {
                    outputTreeHasher.addLeaf(serialized);

                    // Also update running hashes
                    final var hashedLeaf = BlockImplUtils.hashLeaf(serialized);
                    runningHashManager.nextResultHash(ByteBuffer.wrap(hashedLeaf));
                }
                case TRANSACTION_OUTPUT, BLOCK_HEADER -> outputTreeHasher.addLeaf(serialized);
                case STATE_CHANGES -> stateChangesHasher.addLeaf(serialized);
                case TRACE_DATA -> traceDataHasher.addLeaf(serialized);
                case BLOCK_FOOTER, BLOCK_PROOF -> {
                    // BlockFooter and BlockProof are not included in any merkle tree
                    // They are metadata about the block, not part of the hashed content
                }
            }

            final BlockHeader header = item.blockHeader();
            if (header != null) {
                writer.openBlock(header.number());
            }
            writer.writePbjItemAndBytes(item, Bytes.wrap(serialized));

            next.send();
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            log.error("Error occurred while executing task", t);
        }

        void send(SequentialTask next) {
            this.next = next;
            send();
        }

        void send(BlockItem item, byte[] serialized) {
            this.item = item;
            this.serialized = serialized;
            send();
        }
    }

    private SemanticVersion hapiVersionFrom(@NonNull final Configuration config) {
        return config.getConfigData(VersionConfig.class).hapiVersion();
    }

    private static class RunningHashManager {
        private static final ThreadLocal<byte[]> HASHES = ThreadLocal.withInitial(() -> new byte[HASH_SIZE]);
        private static final ThreadLocal<MessageDigest> DIGESTS =
                ThreadLocal.withInitial(CommonUtils::sha384DigestOrThrow);

        byte[] nMinus3Hash;
        byte[] nMinus2Hash;
        byte[] nMinus1Hash;
        byte[] hash;

        Bytes latestHashes() {
            final var all = new byte[][] {nMinus3Hash, nMinus2Hash, nMinus1Hash, hash};
            int numMissing = 0;
            while (numMissing < all.length && all[numMissing] == null) {
                numMissing++;
            }
            final byte[] hashes = new byte[(all.length - numMissing) * HASH_SIZE];
            for (int i = numMissing; i < all.length; i++) {
                System.arraycopy(all[i], 0, hashes, (i - numMissing) * HASH_SIZE, HASH_SIZE);
            }
            return Bytes.wrap(hashes);
        }

        /**
         * Starts managing running hashes for a new round, with the given trailing block hashes.
         *
         * @param blockStreamInfo the trailing block hashes at the start of the round
         */
        void startBlock(@NonNull final BlockStreamInfo blockStreamInfo) {
            final var hashes = blockStreamInfo.trailingOutputHashes();
            final var n = (int) (hashes.length() / HASH_SIZE);
            nMinus3Hash = n < 4 ? null : hashes.toByteArray(0, HASH_SIZE);
            nMinus2Hash = n < 3 ? null : hashes.toByteArray((n - 3) * HASH_SIZE, HASH_SIZE);
            nMinus1Hash = n < 2 ? null : hashes.toByteArray((n - 2) * HASH_SIZE, HASH_SIZE);
            hash = n < 1 ? new byte[HASH_SIZE] : hashes.toByteArray((n - 1) * HASH_SIZE, HASH_SIZE);
        }

        /**
         * Updates the running hashes for the given serialized output block item.
         *
         * @param hash the serialized output block item
         */
        void nextResultHash(@NonNull final ByteBuffer hash) {
            requireNonNull(hash);
            nMinus3Hash = nMinus2Hash;
            nMinus2Hash = nMinus1Hash;
            nMinus1Hash = this.hash;
            final var digest = DIGESTS.get();
            digest.update(this.hash);
            final var resultHash = HASHES.get();
            hash.get(resultHash);
            digest.update(resultHash);
            this.hash = digest.digest();
        }
    }

    private class BlockHashManager {
        final int numTrailingBlocks;

        private Bytes blockHashes;

        BlockHashManager(@NonNull final Configuration config) {
            this.numTrailingBlocks =
                    config.getConfigData(BlockRecordStreamConfig.class).numOfBlockHashesInState();
        }

        /**
         * Starts managing running hashes for a new round, with the given trailing block hashes.
         *
         * @param blockStreamInfo the trailing block hashes at the start of the round
         */
        void startBlock(@NonNull final BlockStreamInfo blockStreamInfo, @NonNull Bytes prevBlockHash) {
            blockHashes = appendHash(prevBlockHash, blockStreamInfo.trailingBlockHashes(), numTrailingBlocks);
        }

        /**
         * Returns the hash of the block with the given number, or null if it is not available. Note that,
         * <ul>
         *     <li>We never know the hash of the {@code N+1} block currently being created.</li>
         *     <li>We start every block {@code N} by concatenating the {@code N-1} block hash to the trailing
         *     hashes up to block {@code N-2} that were in state at the end of block {@code N-1}.
         * </ul>
         *
         * @param blockNo the block number
         * @return the hash of the block with the given number, or null if it is not available
         */
        @Nullable
        Bytes hashOfBlock(final long blockNo) {
            return BlockRecordInfoUtils.blockHashByBlockNumber(blockHashes, blockNumber - 1, blockNo);
        }

        /**
         * Returns the trailing block hashes.
         *
         * @return the trailing block hashes
         */
        Bytes blockHashes() {
            return blockHashes;
        }
    }

    @Override
    public void notify(@NonNull final StateHashedNotification notification) {
        endRoundStateHashes
                .get(notification.round())
                .complete(notification.hash().getBytes());
    }

    @Override
    public void notifyFatalEvent() {
        fatalShutdownFuture = new CompletableFuture<>();
    }

    @Override
    public void awaitFatalShutdown(@NonNull final java.time.Duration timeout) {
        requireNonNull(timeout);
        log.fatal("Awaiting any in-progress round to be closed within {}", timeout);
        Optional.ofNullable(fatalShutdownFuture)
                .orElse(CompletableFuture.completedFuture(null))
                .completeOnTimeout(null, timeout.toSeconds(), TimeUnit.SECONDS)
                .join();
        log.fatal("Block stream fatal shutdown complete");
    }

    @Override
    public void trackEventHash(@NonNull Hash eventHash) {
        eventIndexInBlock.put(eventHash, eventIndex++);
    }

    @Override
    public Optional<Integer> getEventIndex(@NonNull Hash eventHash) {
        return Optional.ofNullable(eventIndexInBlock.get(eventHash));
    }

    private BlockItem flushChangesFromListener(@NonNull final BoundaryStateChangeListener boundaryStateChangeListener) {
        final var stateChanges = new StateChanges(lastUsedTime, boundaryStateChangeListener.allStateChanges());
        boundaryStateChangeListener.reset();
        return BlockItem.newBuilder().stateChanges(stateChanges).build();
    }

    /**
     * Resets the subtree hashers for branches 4-8 to empty states. Since these subtrees only contain data specific to
     * the current block, they need to be reset whenever a new block starts.
     */
    private void resetSubtrees() {
        // Branch 4
        consensusHeaderHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        // Branch 5
        inputTreeHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        // Branch 6
        outputTreeHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        // Branch 7
        stateChangesHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        // Branch 8
        traceDataHasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
    }

    private record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    /**
     * Combines the given branch hashes into a block root hash and sibling hashes for a pending proof.
     * Since it's not known whether the pending proof will be directly signed at this point in the block's
     * lifecycle, the sibling hashes required for an indirect proof are also computed.
     * <p>
     * Note that all of these initial hash values should have all been hashed prior to this point, whether
     * as a leaf node (prefixed with {@link StreamingTreeHasher#LEAF_PREFIX}) or as an internal node (prefixed
     * with {@link StreamingTreeHasher#INTERNAL_NODE_PREFIX}). Therefore, they should <b>not</b> be hashed
     * again until combined with another hash.
     * <p>
     * While {@code prevBlockHash} could programmatically be null, in practice it never should be. Even
     * in the case of the genesis block, this value should be {@link BlockStreamManager#ZERO_BLOCK_HASH}. For
     * all other blocks, it should be the actual previous block's root hash.
     * @return the block root hash and all possibly-required sibling hashes, ordered from bottom (the
     * leaf level, depth six) to top (the root, depth one)
     */
    private static RootAndSiblingHashes combine(
            @NonNull final Bytes prevBlockHash,
            @NonNull final Bytes prevBlockRootsHash,
            @NonNull final Bytes startingStateHash,
            @NonNull final Bytes consensusHeaderHash,
            @NonNull final Bytes inputsHash,
            @NonNull final Bytes outputsHash,
            @NonNull final Bytes stateChangesHash,
            @NonNull final Bytes traceDataHash,
            @NonNull final Timestamp firstConsensusTimeOfCurrentBlock) {
        requireNonNull(prevBlockHash);

        // Compute depth five hashes
        final var depth5Node1 = BlockImplUtils.hashInternalNode(prevBlockHash, prevBlockRootsHash);
        final var depth5Node2 = BlockImplUtils.hashInternalNode(startingStateHash, consensusHeaderHash);
        final var depth5Node3 = BlockImplUtils.hashInternalNode(inputsHash, outputsHash);
        final var depth5Node4 = BlockImplUtils.hashInternalNode(stateChangesHash, traceDataHash);

        // Compute depth four hashes
        final var depth4Node1 = BlockImplUtils.hashInternalNode(depth5Node1, depth5Node2);
        final var depth4Node2 = BlockImplUtils.hashInternalNode(depth5Node3, depth5Node4);

        // Compute depth three hash (there's no node 2 hash as the reserved subroots aren't encoded anywhere in the
        // tree)
        final var depth3Node1 = BlockImplUtils.hashInternalNode(depth4Node1, depth4Node2);

        // Compute depth two hashes (timestamp + last right sibling)
        final var tsBytes = Timestamp.PROTOBUF.toBytes(firstConsensusTimeOfCurrentBlock);
        final var depth2Node1 = hashLeaf(tsBytes);
        // (Depth 2, Node 2) represents the subroot of the tree where the actual data combine with the future reserved
        // roots 9-16, so we treat its child as the only child (even though other future roots may exist later).
        final var depth2Node2 = BlockImplUtils.hashInternalNodeSingleChild(depth3Node1);

        // Compute the block's root hash (depth 1)
        final var rootHash = BlockImplUtils.hashInternalNode(depth2Node1, depth2Node2);

        return new RootAndSiblingHashes(rootHash, new MerkleSiblingHash[] {
            // Level 6 first sibling (right child)
            new MerkleSiblingHash(false, prevBlockRootsHash),
            // Level 5 first sibling (right child)
            new MerkleSiblingHash(false, depth5Node2),
            // Level 4 first sibling (right child)
            new MerkleSiblingHash(false, depth4Node2)
            // Level 3 has no sibling because reserved roots 9-16 aren't represented as real subroots in the tree. It's
            // just a single child node hash operation
            // Level 2's _left_ sibling–the block's timestamp–is represented explicitly outside these sibling hashes
        });
    }

    private static Instant firstConsensusTimestampOf(final Round round) {
        Instant earliestEventTimestamp = null;
        for (final ConsensusEvent consensusEvent : round) {
            // Find the earliest event timestamp in the round (possibly needed later)
            if (earliestEventTimestamp == null) {
                final var eventTimestamp = consensusEvent.getConsensusTimestamp();
                if (eventTimestamp != null
                        && eventTimestamp.isAfter(Instant.EPOCH)
                        && eventTimestamp.isBefore(round.getConsensusTimestamp())) {
                    earliestEventTimestamp = eventTimestamp;
                }
            }

            // Iterate through the transactions in the event to find the first consensus timestamp. If found, return it
            // immediately.
            final var consensusIt = consensusEvent.consensusTransactionIterator();
            if (consensusIt.hasNext()) {
                return consensusIt.next().getConsensusTimestamp();
            }
        }

        // If the round has no transactions, but we found an earliest event timestamp, return the earliest event
        // timestamp
        if (earliestEventTimestamp != null) {
            return earliestEventTimestamp;
        }

        // If the round has no event timestamps, return the round's timestamp
        return round.getConsensusTimestamp();
    }

    private static int maxReadDepth(@NonNull final Configuration config) {
        requireNonNull(config);
        return config.getConfigData(BlockStreamConfig.class).maxReadDepth();
    }

    private static int maxReadBytesSize(@NonNull final Configuration config) {
        requireNonNull(config);
        return config.getConfigData(BlockStreamConfig.class).maxReadBytesSize();
    }
}
