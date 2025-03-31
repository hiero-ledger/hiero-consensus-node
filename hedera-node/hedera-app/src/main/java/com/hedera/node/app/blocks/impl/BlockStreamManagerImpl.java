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
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
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
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.DiskNetworkExport;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.concurrent.AbstractTask;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
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
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.Round;

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
    private final Supplier<BlockItemWriter> writerSupplier;
    private final BoundaryStateChangeListener boundaryStateChangeListener;
    private final PlatformStateFacade platformStateFacade;

    private final Lifecycle lifecycle;
    private final BlockHashManager blockHashManager;
    private final RunningHashManager runningHashManager;

    // The status of pending work
    private PendingWork pendingWork = NONE;
    // The last time at which interval-based processing was done
    private Instant lastIntervalProcessTime = Instant.EPOCH;
    // The last platform-assigned time
    private Instant lastHandleTime = Instant.EPOCH;
    // All this state is scoped to producing the current block
    private long blockNumber;
    // Set to the round number of the last round handled before entering a freeze period
    private long freezeRoundNumber = -1;
    // The last non-empty (i.e., not skipped) round number that will eventually get a start-of-state hash
    private long lastNonEmptyRoundNumber;
    private Bytes lastBlockHash;
    private Instant blockTimestamp;
    private Instant consensusTimeLastRound;
    private BlockItemWriter writer;
    private StreamingTreeHasher inputTreeHasher;
    private StreamingTreeHasher outputTreeHasher;
    private BlockStreamManagerTask worker;
    private final boolean hintsEnabled;
    // If not null, the part of the block preceding a possible first transaction
    @Nullable
    private PreTxnItems preTxnItems;

    /**
     * Represents the part of a block preceding a possible first transaction; we defer writing this part until
     * we know the timestamp of the first transaction.
     * <p>
     * <b>Important:</b> This first timestamp may be different from the first platform-assigned user transaction
     * time because of synthetic preceding transactions.
     *
     * @param headerBuilder   the block header builder
     * @param postHeaderItems the post-header items
     */
    private record PreTxnItems(@NonNull BlockHeader.Builder headerBuilder, @NonNull List<BlockItem> postHeaderItems) {}

    /**
     * Represents a block pending completion by the block hash signature needed for its block proof.
     *
     * @param number        the block number
     * @param blockHash     the block hash
     * @param proofBuilder  the block proof builder
     * @param writer        the block item writer
     * @param siblingHashes the sibling hashes needed for an indirect block proof of an earlier block
     */
    private record PendingBlock(
            long number,
            @NonNull Bytes blockHash,
            @NonNull BlockProof.Builder proofBuilder,
            @NonNull BlockItemWriter writer,
            @NonNull MerkleSiblingHash... siblingHashes) {}

    /**
     * Blocks awaiting proof via ledger signature on their block hash (or a subsequent block hash).
     */
    private final Queue<PendingBlock> pendingBlocks = new ConcurrentLinkedQueue<>();
    /**
     * Futures that resolve when the end-of-round state hash is available for a given round number.
     */
    private final Map<Long, CompletableFuture<Bytes>> endRoundStateHashes = new ConcurrentHashMap<>();

    @Nullable
    private volatile CompletableFuture<Void> fatalShutdownFuture = null;

    @Inject
    public BlockStreamManagerImpl(
            @NonNull final BlockHashSigner blockHashSigner,
            @NonNull final Supplier<BlockItemWriter> writerSupplier,
            @NonNull final ExecutorService executor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final InitialStateHash initialStateHash,
            @NonNull final SemanticVersion version,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Lifecycle lifecycle) {
        this.blockHashSigner = requireNonNull(blockHashSigner);
        this.version = requireNonNull(version);
        this.writerSupplier = requireNonNull(writerSupplier);
        this.executor = (ForkJoinPool) requireNonNull(executor);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.lifecycle = requireNonNull(lifecycle);
        final var config = configProvider.getConfiguration();
        this.hintsEnabled = config.getConfigData(TssConfig.class).hintsEnabled();
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
        this.lastNonEmptyRoundNumber = initialStateHash.roundNum();
        final var hashFuture = initialStateHash.hashFuture();
        endRoundStateHashes.put(lastNonEmptyRoundNumber, hashFuture);
        log.info(
                "Initialized BlockStreamManager from round {} with end-of-round hash {}",
                lastNonEmptyRoundNumber,
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

        if (platformStateFacade.isFreezeRound(state, round)) {
            // Track freeze round numbers because they always end a block
            freezeRoundNumber = round.getRoundNum();
        }

        // Writer will be null when beginning a new block
        if (writer == null) {
            writer = writerSupplier.get();
            // This iterator is never empty; c.f. DefaultTransactionHandler#handleConsensusRound()
            blockTimestamp = round.iterator().next().getConsensusTimestamp();
            boundaryStateChangeListener.setBoundaryTimestamp(blockTimestamp);

            final var blockStreamInfo = blockStreamInfoFrom(state);
            pendingWork = classifyPendingWork(blockStreamInfo, version);
            if (pendingWork == POST_UPGRADE_WORK && hintsEnabled) {
                // On upgrade, we need to gossip the signatures for the freeze block
                blockHashSigner
                        .signFuture(lastBlockHash)
                        .thenAcceptAsync(signature -> finishProofWithSignature(lastBlockHash, signature));
            }
            lastHandleTime = asInstant(blockStreamInfo.lastHandleTimeOrElse(EPOCH));
            lastIntervalProcessTime = asInstant(blockStreamInfo.lastIntervalProcessTimeOrElse(EPOCH));
            blockHashManager.startBlock(blockStreamInfo, lastBlockHash);
            runningHashManager.startBlock(blockStreamInfo);

            lifecycle.onOpenBlock(state);
            inputTreeHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            outputTreeHasher = new ConcurrentStreamingTreeHasher(executor, hashCombineBatchSize);
            blockNumber = blockStreamInfo.blockNumber() + 1;

            worker = new BlockStreamManagerTask();
            final var header = BlockHeader.newBuilder()
                    .number(blockNumber)
                    .hashAlgorithm(SHA2_384)
                    .softwareVersion(platformStateFacade.creationSemanticVersionOf(state))
                    .hapiProtoVersion(hapiVersion);
            preTxnItems = new PreTxnItems(header, new ArrayList<>());
        }
        consensusTimeLastRound = round.getConsensusTimestamp();
    }

    @Override
    public void setRoundFirstTransactionTime(@NonNull final Instant at) {
        if (preTxnItems != null) {
            flushPreUserItems(asTimestamp(at));
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
    public @NonNull final Instant lastHandleTime() {
        return lastHandleTime;
    }

    @Override
    public void setLastHandleTime(@NonNull final Instant lastHandleTime) {
        this.lastHandleTime = requireNonNull(lastHandleTime);
    }

    @Override
    public boolean endRound(@NonNull final State state, final long roundNum) {
        final boolean closesBlock = shouldCloseBlock(roundNum, roundsPerBlock);
        if (closesBlock) {
            // If there were no user or node transactions in the block, this writes all
            // the accumulated items starting from the header, sacrificing the benefits
            // of concurrency; but performance impact is irrelevant when there are no
            // transactions of any sort
            if (preTxnItems != null) {
                flushPreUserItems(null);
            }
            lifecycle.onCloseBlock(state);
            // Flush all boundary state changes besides the BlockStreamInfo
            worker.addItem(boundaryStateChangeListener.flushChanges());
            worker.sync();

            final var inputHash = inputTreeHasher.rootHash().join();
            // This block's starting state hash is the end state hash of the last non-empty round
            final var blockStartStateHash = requireNonNull(endRoundStateHashes.get(lastNonEmptyRoundNumber))
                    .join();
            // Now clean up hash futures for rounds before the one closing this block
            for (long i = lastNonEmptyRoundNumber; i < roundNum; i++) {
                endRoundStateHashes.remove(i);
            }
            // And update the last non-empty round number to this round
            lastNonEmptyRoundNumber = roundNum;
            final var outputTreeStatus = outputTreeHasher.status();

            // Put this block hash context in state via the block stream info
            final var writableState = state.getWritableStates(BlockStreamService.NAME);
            final var blockStreamInfoState = writableState.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY);
            final var boundaryTimestamp = boundaryStateChangeListener.boundaryTimestampOrThrow();
            blockStreamInfoState.put(new BlockStreamInfo(
                    blockNumber,
                    blockTimestamp(),
                    runningHashManager.latestHashes(),
                    blockHashManager.blockHashes(),
                    inputHash,
                    blockStartStateHash,
                    outputTreeStatus.numLeaves(),
                    outputTreeStatus.rightmostHashes(),
                    boundaryTimestamp,
                    pendingWork != POST_UPGRADE_WORK,
                    version,
                    asTimestamp(lastIntervalProcessTime),
                    asTimestamp(lastHandleTime)));
            ((CommittableWritableStates) writableState).commit();

            worker.addItem(boundaryStateChangeListener.flushChanges());
            worker.sync();

            final var outputHash = outputTreeHasher.rootHash().join();
            final var leftParent = combine(lastBlockHash, inputHash);
            final var rightParent = combine(outputHash, blockStartStateHash);
            final var blockHash = combine(leftParent, rightParent);
            final var pendingProof = BlockProof.newBuilder()
                    .block(blockNumber)
                    .previousBlockRootHash(lastBlockHash)
                    .startOfBlockStateRootHash(blockStartStateHash);
            pendingBlocks.add(new PendingBlock(
                    blockNumber,
                    blockHash,
                    pendingProof,
                    writer,
                    new MerkleSiblingHash(false, inputHash),
                    new MerkleSiblingHash(false, rightParent)));
            // Update in-memory state to prepare for the next block
            lastBlockHash = blockHash;
            writer = null;
            // Special case when signing with hinTS and this is the freeze round; we will have to wait until
            // after restart to gossip partial signatures and sign this block
            if (hintsEnabled && roundNum == freezeRoundNumber) {
                pendingBlocks.forEach(block -> block.writer().closeBlock());
            } else {
                blockHashSigner
                        .signFuture(blockHash)
                        .thenAcceptAsync(signature -> finishProofWithSignature(blockHash, signature));
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
        }
        if (fatalShutdownFuture != null) {
            pendingBlocks.forEach(block -> log.fatal("Skipping incomplete block proof for block {}", block.number()));
            if (writer != null) {
                log.fatal("Prematurely closing block {}", blockNumber);
                writer.closeBlock();
                writer = null;
            }
            requireNonNull(fatalShutdownFuture).complete(null);
        }
        return closesBlock || lastNonEmptyRoundNumber == 0;
    }

    @Override
    public void writeItem(@NonNull final BlockItem item) {
        if (preTxnItems != null) {
            preTxnItems.postHeaderItems().add(item);
        } else {
            worker.addItem(item);
        }
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
     * @param blockHash      the block hash to finish the block proof for
     * @param blockSignature the signature to use in the block proof
     */
    private synchronized void finishProofWithSignature(
            @NonNull final Bytes blockHash, @NonNull final Bytes blockSignature) {
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
            log.info("Ignoring signature on already proven block hash '{}'", blockHash);
            return;
        }
        // Write proofs for all pending blocks up to and including the signed block number
        while (!pendingBlocks.isEmpty() && pendingBlocks.peek().number() <= blockNumber) {
            final var block = pendingBlocks.poll();
            final var proof = block.proofBuilder()
                    .blockSignature(blockSignature)
                    .siblingHashes(siblingHashes.stream().flatMap(List::stream).toList());
            final var proofItem = BlockItem.newBuilder().blockProof(proof).build();
            block.writer().writePbjItemAndBytes(proofItem, BlockItem.PROTOBUF.toBytes(proofItem));
            block.writer().closeBlock();
            if (block.number() != blockNumber) {
                siblingHashes.removeFirst();
            }
        }
    }

    /**
     * Flushes the pre-user items, with the given first user transaction time.
     *
     * @param firstUserTransactionTime the first user transaction time
     */
    private void flushPreUserItems(@Nullable final Timestamp firstUserTransactionTime) {
        requireNonNull(preTxnItems);
        final var header = preTxnItems.headerBuilder().firstTransactionConsensusTime(firstUserTransactionTime);
        worker.addItem(BlockItem.newBuilder().blockHeader(header).build());
        preTxnItems.postHeaderItems().forEach(worker::addItem);
        preTxnItems = null;
    }

    /**
     * Classifies the type of work pending, if any, given the block stream info from state and the current
     * software version.
     *
     * @param blockStreamInfo the block stream info
     * @param version         the version
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
        final var blockStreamInfoState =
                state.getReadableStates(BlockStreamService.NAME).<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY);
        return requireNonNull(blockStreamInfoState.get());
    }

    private boolean shouldCloseBlock(final long roundNumber, final int roundsPerBlock) {
        if (fatalShutdownFuture != null) {
            return true;
        }
        // We need the signer to be ready
        if (!blockHashSigner.isReady()) {
            return false;
        }

        // During freeze round, we should close the block regardless of other conditions
        if (roundNumber == freezeRoundNumber) {
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
            Bytes bytes = BlockItem.PROTOBUF.toBytes(item);

            final var kind = item.item().kind();
            ByteBuffer hash = null;
            switch (kind) {
                case EVENT_HEADER,
                        EVENT_TRANSACTION,
                        TRANSACTION_RESULT,
                        TRANSACTION_OUTPUT,
                        STATE_CHANGES,
                        ROUND_HEADER,
                        BLOCK_HEADER -> {
                    MessageDigest digest = sha384DigestOrThrow();
                    bytes.writeTo(digest);
                    hash = ByteBuffer.wrap(digest.digest());
                }
            }
            out.send(item, hash, bytes);
            return true;
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
                case EVENT_HEADER, EVENT_TRANSACTION, ROUND_HEADER -> inputTreeHasher.addLeaf(hash);
                case TRANSACTION_RESULT -> {
                    runningHashManager.nextResultHash(hash);
                    hash.rewind();
                    outputTreeHasher.addLeaf(hash);
                }
                case TRANSACTION_OUTPUT, STATE_CHANGES, BLOCK_HEADER -> outputTreeHasher.addLeaf(hash);
            }

            final BlockHeader header = item.blockHeader();
            if (header != null) {
                writer.openBlock(header.number());
            }
            writer.writePbjItemAndBytes(item, serialized);

            next.send();
            return true;
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
}
