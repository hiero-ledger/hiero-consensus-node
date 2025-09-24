// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_384;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.GENESIS_WORK;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.NONE;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.POST_UPGRADE_WORK;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.blockDirFor;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.cleanUpPendingBlock;
import static com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter.loadContiguousPendingBlocks;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.HederaStateRoot;
import com.hedera.node.app.HederaVirtualMapState;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.info.DiskStartupNetworks.InfoType;
import com.hedera.node.app.records.impl.BlockRecordInfoUtils;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.DiskNetworkExport;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.hashgraph.Round;

@Singleton
public class BlockStreamManagerImpl implements BlockStreamManager {

    private static final Logger log = LogManager.getLogger(BlockStreamManagerImpl.class);
    public static final Bytes NULL_HASH = Bytes.wrap(new byte[HASH_SIZE]);

    private final int roundsPerBlock;
    private final Duration blockPeriod;
    private final int hashCombineBatchSize;
    private final BlockHashSigner blockHashSigner;
    private final SemanticVersion version;
    private final SemanticVersion hapiVersion;
    private final ForkJoinPool executor;
    private final String diskNetworkExportFile;
    private final DiskNetworkExport diskNetworkExport;
    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;
    private final Supplier<BlockItemWriter> writerSupplier;
    private final BoundaryStateChangeListener boundaryStateChangeListener;
    private final PlatformStateFacade platformStateFacade;

    private final Lifecycle lifecycle;
    private final BlockHashManager blockHashManager;
    private final RunningHashManager runningHashManager;
    private final boolean streamToBlockNodes;

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
    private final Map<Hash, Integer> eventIndexInBlock = new HashMap<>();
    // The last non-empty (i.e., not skipped) round number that will eventually get a start-of-state hash
    private long lastRoundOfPrevBlock;
    private Bytes lastBlockHash;
    private Instant blockTimestamp;
    private Instant consensusTimeLastRound;
    private Timestamp lastUsedTime;
    private BlockItemWriter writer;
    // stream hashers
    private StreamingTreeHasher inputTreeHasher;
    private StreamingTreeHasher outputTreeHasher;
    private StreamingTreeHasher consensusHeaderHasher;
    private StreamingTreeHasher stateChangesHasher;
    private StreamingTreeHasher traceDataHasher;

    private BlockStreamManagerTask worker;
    private final boolean hintsEnabled;

    /**
     * Represents a block pending completion by the block hash signature needed for its block proof.
     *
     * @param number the block number
     * @param contentsPath the path to the block contents file, if not null
     * @param blockHash the block hash
     * @param proofBuilder the block proof builder
     * @param writer the block item writer
     * @param siblingHashes the sibling hashes needed for an indirect block proof of an earlier block
     */
    private record PendingBlock(
            long number,
            @Nullable Path contentsPath,
            @NonNull Bytes blockHash,
            @NonNull BlockProof.Builder proofBuilder,
            @NonNull BlockItemWriter writer,
            @NonNull MerkleSiblingHash... siblingHashes) {
        /**
         * Flushes this pending block to disk, optionally including the sibling hashes needed
         * for an indirect proof of its preceding block(s).
         *
         * @param withSiblingHashes whether to include sibling hashes for an indirect proof
         */
        public void flushPending(final boolean withSiblingHashes) {
            final var incompleteProof = proofBuilder.build();
            final var pendingProof = PendingProof.newBuilder()
                    .block(number)
                    .blockHash(blockHash)
                    .previousBlockHash(incompleteProof.previousBlockRootHash())
                    .startOfBlockStateRootHash(incompleteProof.startOfBlockStateRootHash())
                    .siblingHashesFromPrevBlockRoot(withSiblingHashes ? List.of(siblingHashes) : List.of())
                    .build();
            writer.flushPendingBlock(pendingProof);
        }
    }

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
            @NonNull final NetworkInfo networkInfo,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final InitialStateHash initialStateHash,
            @NonNull final SemanticVersion version,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Lifecycle lifecycle,
            @NonNull final Metrics metrics) {
        this.blockHashSigner = requireNonNull(blockHashSigner);
        this.networkInfo = requireNonNull(networkInfo);
        this.version = requireNonNull(version);
        this.writerSupplier = requireNonNull(writerSupplier);
        this.executor = (ForkJoinPool) requireNonNull(executor);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.lifecycle = requireNonNull(lifecycle);
        this.configProvider = requireNonNull(configProvider);
        final var config = configProvider.getConfiguration();
        this.hintsEnabled = config.getConfigData(TssConfig.class).hintsEnabled();
        this.hapiVersion = hapiVersionFrom(config);
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        this.roundsPerBlock = blockStreamConfig.roundsPerBlock();
        this.blockPeriod = blockStreamConfig.blockPeriod();
        this.hashCombineBatchSize = blockStreamConfig.hashCombineBatchSize();
        this.streamToBlockNodes = blockStreamConfig.streamToBlockNodes();
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
        log.info(
                "Initialized BlockStreamManager from round {} with end-of-round hash {}",
                lastRoundOfPrevBlock,
                hashFuture.isDone() ? hashFuture.join().toHex() : "<PENDING>");
    }

    @Override
    public boolean hasLedgerId() {
        return blockHashSigner.isReady();
    }

    @Override
    public void initLastBlockHash(@NonNull final Bytes blockHash) {
        lastBlockHash = requireNonNull(blockHash);
    }

    @Override
    public void startRound(@NonNull final Round round, @NonNull final State state) {
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
            blockTimestamp = round.getConsensusTimestamp();
            lastUsedTime = asTimestamp(round.getConsensusTimestamp());

            final var blockStreamInfo = blockStreamInfoFrom(state);
            pendingWork = classifyPendingWork(blockStreamInfo, version);
            lastTopLevelTime = asInstant(blockStreamInfo.lastHandleTimeOrElse(EPOCH));
            lastIntervalProcessTime = asInstant(blockStreamInfo.lastIntervalProcessTimeOrElse(EPOCH));
            blockHashManager.startBlock(blockStreamInfo, lastBlockHash);
            runningHashManager.startBlock(blockStreamInfo);

            lifecycle.onOpenBlock(state);

            inputTreeHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            outputTreeHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            consensusHeaderHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            stateChangesHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            traceDataHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);

            blockNumber = blockStreamInfo.blockNumber() + 1;
            if (hintsEnabled && !hasCheckedForPendingBlocks) {
                final var hasBeenFrozen = requireNonNull(state.getReadableStates(PlatformStateService.NAME)
                                .<PlatformState>getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID)
                                .get())
                        .hasLastFrozenTime();
                if (hasBeenFrozen) {
                    recoverPendingBlocks();
                }
                hasCheckedForPendingBlocks = true;
            }

            worker = new BlockStreamManagerTask();
            final var header = BlockHeader.newBuilder()
                    .number(blockNumber)
                    .hashAlgorithm(SHA2_384)
                    .softwareVersion(platformStateFacade.creationSemanticVersionOf(state))
                    .blockTimestamp(asTimestamp(blockTimestamp))
                    .hapiProtoVersion(hapiVersion);
            worker.addItem(BlockItem.newBuilder().blockHeader(header).build());
        }
        consensusTimeLastRound = round.getConsensusTimestamp();
    }

    /**
     * Recovers the contents and proof context of any pending blocks from disk.
     */
    private void recoverPendingBlocks() {
        final var blockDirPath = blockDirFor(configProvider.getConfiguration());
        log.info(
                "Attempting to recover any pending blocks contiguous to #{} still on disk @ {}",
                blockNumber,
                blockDirPath.toAbsolutePath());
        try {
            final var onDiskPendingBlocks = loadContiguousPendingBlocks(blockDirPath, blockNumber);
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
                    final var blockHash = block.blockHash();
                    pendingBlocks.add(new PendingBlock(
                            block.number(),
                            block.contentsPath(),
                            blockHash,
                            block.proofBuilder(),
                            pendingWriter,
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
    public boolean endRound(@NonNull final State state, final long roundNum) {
        final var storeFactory = new ReadableStoreFactory(state);
        final var platformStateStore = storeFactory.getStore(ReadablePlatformStateStore.class);
        final long freezeRoundNumber = platformStateStore.getLatestFreezeRound();
        final boolean closesBlock = shouldCloseBlock(roundNum, freezeRoundNumber);
        if (closesBlock) {
            lifecycle.onCloseBlock(state);
            if (state instanceof HederaVirtualMapState hederaNewStateRoot) {
                hederaNewStateRoot.commitSingletons();
            } else if (state instanceof HederaStateRoot hederaStateRoot) {
                // Non production case (testing tools)
                // Otherwise assume it is a MerkleStateRoot
                // This branch should be removed once the MerkleStateRoot is removed
                hederaStateRoot.commitSingletons();
            }
            // Flush all boundary state changes besides the BlockStreamInfo

            worker.addItem(flushChangesFromListener(boundaryStateChangeListener));
            worker.sync();

            final var consensusHeaderHash = consensusHeaderHasher.rootHash().join();
            final var inputHash = inputTreeHasher.rootHash().join();
            final var traceDataHash = traceDataHasher.rootHash().join();
            final var outputHash = outputTreeHasher.rootHash().join();

            // This block's starting state hash is the end state hash of the last non-empty round
            final var blockStartStateHash = requireNonNull(endRoundStateHashes.get(lastRoundOfPrevBlock))
                    .join();
            // Now clean up hash futures for rounds before the one closing this block
            for (long i = lastRoundOfPrevBlock; i < roundNum; i++) {
                endRoundStateHashes.remove(i);
            }
            // And update the last non-empty round number to this round
            lastRoundOfPrevBlock = roundNum;
            final var stateChangesTreeStatus = stateChangesHasher.status();

            // Put this block hash context in state via the block stream info
            final var writableState = state.getWritableStates(BlockStreamService.NAME);
            final var blockStreamInfoState = writableState.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
            blockStreamInfoState.put(new BlockStreamInfo(
                    blockNumber,
                    blockTimestamp(),
                    runningHashManager.latestHashes(),
                    blockHashManager.blockHashes(),
                    inputHash,
                    blockStartStateHash,
                    stateChangesTreeStatus.numLeaves(),
                    stateChangesTreeStatus.rightmostHashes(),
                    lastUsedTime,
                    pendingWork != POST_UPGRADE_WORK,
                    version,
                    asTimestamp(lastIntervalProcessTime),
                    asTimestamp(lastTopLevelTime),
                    consensusHeaderHash,
                    traceDataHash,
                    outputHash));
            ((CommittableWritableStates) writableState).commit();

            worker.addItem(flushChangesFromListener(boundaryStateChangeListener));
            worker.sync();

            final var stateChangesHash = stateChangesHasher.rootHash().join();

            // Compute depth two hashes
            final var depth2Node0 = combine(lastBlockHash, blockStartStateHash);
            final var depth2Node1 = combine(consensusHeaderHash, inputHash);
            final var depth2Node2 = combine(outputHash, stateChangesHash);
            final var depth2Node3 = combine(traceDataHash, NULL_HASH);

            // Compute depth one hashes
            final var depth1Node0 = combine(depth2Node0, depth2Node1);
            final var depth1Node1 = combine(depth2Node2, depth2Node3);

            // Compute the block hash
            final var blockHash = combine(depth1Node0, depth1Node1);

            final var pendingProof = BlockProof.newBuilder()
                    .block(blockNumber)
                    .previousBlockRootHash(lastBlockHash)
                    .startOfBlockStateRootHash(blockStartStateHash);
            pendingBlocks.add(new PendingBlock(
                    blockNumber,
                    null,
                    blockHash,
                    pendingProof,
                    writer,
                    new MerkleSiblingHash(false, blockStartStateHash),
                    new MerkleSiblingHash(false, depth2Node1),
                    new MerkleSiblingHash(false, depth1Node1)));

            // Update in-memory state to prepare for the next block
            lastBlockHash = blockHash;
            writer = null;

            // Special case when signing with hinTS and this is the freeze round; we have to wait
            // until after restart to gossip partial signatures and sign any pending blocks
            if (hintsEnabled && roundNum == freezeRoundNumber) {
                final var hasPrecedingUnproven = new AtomicBoolean(false);
                // In case the id of the next hinTS construction changed since a block endede
                pendingBlocks.forEach(block -> block.flushPending(hasPrecedingUnproven.getAndSet(true)));
            } else {
                final var schemeId = blockHashSigner.schemeId();
                blockHashSigner
                        .signFuture(blockHash)
                        .thenAcceptAsync(signature -> finishProofWithSignature(blockHash, signature, schemeId));
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
                DiskStartupNetworks.writeNetworkInfo(
                        state, exportPath, EnumSet.allOf(InfoType.class), platformStateFacade);
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
        return new Timestamp(blockTimestamp.getEpochSecond(), blockTimestamp.getNano());
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
     *
     * @param blockHash the block hash to finish the block proof for
     * @param blockSignature the signature to use in the block proof
     * @param schemeId the id of the signing scheme used
     */
    private synchronized void finishProofWithSignature(
            @NonNull final Bytes blockHash, @NonNull final Bytes blockSignature, final long schemeId) {
        // Find the block whose hash is the signed message, tracking any sibling hashes
        // needed for indirect proofs of earlier blocks along the way
        long blockNumber = Long.MIN_VALUE;
        boolean impliesIndirectProof = false;
        final List<List<MerkleSiblingHash>> siblingHashes = new ArrayList<>();
        for (final var block : pendingBlocks) {
            if (impliesIndirectProof) {
                siblingHashes.add(List.of(block.siblingHashes()));
            }
            if (block.blockHash().equals(blockHash)) {
                blockNumber = block.number();
                break;
            }
            impliesIndirectProof = true;
        }
        if (blockNumber == Long.MIN_VALUE) {
            log.debug("Ignoring signature on already proven block hash '{}'", blockHash);
            return;
        }
        // Write proofs for all pending blocks up to and including the signed block number
        while (!pendingBlocks.isEmpty() && pendingBlocks.peek().number() <= blockNumber) {
            final var block = pendingBlocks.poll();
            // Update the metrics, if the block is closed with a sibling hash (indirect proof).
            if (!siblingHashes.isEmpty()) {
                indirectProofCounter.increment();
            }
            final var proof = block.proofBuilder()
                    .blockSignature(blockSignature)
                    .siblingHashes(siblingHashes.stream().flatMap(List::stream).toList());
            proof.schemeId(schemeId);
            final var proofItem = BlockItem.newBuilder().blockProof(proof).build();
            block.writer().writePbjItemAndBytes(proofItem, BlockItem.PROTOBUF.toBytes(proofItem));
            block.writer().closeCompleteBlock();
            if (block.number() != blockNumber) {
                siblingHashes.removeFirst();
            }
            if (block.contentsPath() != null) {
                cleanUpPendingBlock(block.contentsPath());
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

    private @NonNull BlockStreamInfo blockStreamInfoFrom(@NonNull final State state) {
        final var blockStreamInfoState = state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID);
        return requireNonNull(blockStreamInfoState.get());
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
        final var elapsed = Duration.between(blockTimestamp, consensusTimeLastRound);
        return elapsed.compareTo(blockPeriod) >= 0;
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
                Bytes bytes = BlockItem.PROTOBUF.toBytes(item);
                final var kind = item.item().kind();
                ByteBuffer hash = null;
                switch (kind) {
                    case EVENT_HEADER,
                            SIGNED_TRANSACTION,
                            TRANSACTION_RESULT,
                            TRANSACTION_OUTPUT,
                            STATE_CHANGES,
                            ROUND_HEADER,
                            BLOCK_HEADER,
                            TRACE_DATA -> {
                        MessageDigest digest = sha384DigestOrThrow();
                        bytes.writeTo(digest);
                        hash = ByteBuffer.wrap(digest.digest());
                    }
                }
                out.send(item, hash, bytes);
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
        Bytes serialized;
        ByteBuffer hash;

        SequentialTask() {
            super(executor, 3);
        }

        @Override
        protected boolean onExecute() {
            final var kind = item.item().kind();
            switch (kind) {
                case ROUND_HEADER, EVENT_HEADER -> consensusHeaderHasher.addLeaf(hash);
                case SIGNED_TRANSACTION -> inputTreeHasher.addLeaf(hash);
                case TRANSACTION_RESULT -> {
                    runningHashManager.nextResultHash(hash);
                    hash.rewind();
                    outputTreeHasher.addLeaf(hash);
                }
                case TRANSACTION_OUTPUT, BLOCK_HEADER -> outputTreeHasher.addLeaf(hash);
                case STATE_CHANGES -> stateChangesHasher.addLeaf(hash);
                case TRACE_DATA -> traceDataHasher.addLeaf(hash);
            }

            final BlockHeader header = item.blockHeader();
            if (header != null) {
                writer.openBlock(header.number());
            }
            writer.writePbjItemAndBytes(item, serialized);

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

        void send(BlockItem item, ByteBuffer hash, Bytes serialized) {
            this.item = item;
            this.hash = hash;
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
}
