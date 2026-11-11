// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.gcp;

import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileName;
import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileUri;
import static com.hedera.statevalidation.gcp.GcpPathHelper.listFirstFile;
import static com.hedera.statevalidation.gcp.GcpPathHelper.listLastFile;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/**
 * Determines the range of block files {@code [leftBlock, rightBlock]} that need to be downloaded
 * from GCS to apply blocks up to a target round.
 *
 * <b>Algorithm overview</b>
 * <br>
 * <ol>
 *   <li><b>Left boundary</b>: Read {@link BlockStreamInfo} singleton from the loaded state;
 *       extract {@code blockNumber + 1}.</li>
 *   <li><b>Find extent of available blocks</b>: Scatter-gather existence probes at exponentially
 *       spaced block numbers, then refine the exact last available block.</li>
 *   <li><b>Find target-round block</b>: Scatter-gather binary search — download candidate
 *       {@code .blk.gz} files, parse {@code RoundHeader} items, narrow the range until the block
 *       containing the target round is found.</li>
 * </ol>
 *
 * <p>Probe files are downloaded to the same temp directory that will hold the final bulk download,
 * so they effectively serve as pre-cached files and are not re-downloaded later.
 */
public final class BlockRangeResolver {

    private static final Logger log = LogManager.getLogger(BlockRangeResolver.class);

    private static final Marker CONSOLE = GcpPathHelper.CONSOLE;

    /** The state ID for the BlockStreamInfo singleton. */
    private static final int BLOCK_STREAM_INFO_STATE_ID =
            SingletonType.BLOCKSTREAMSERVICE_I_BLOCK_STREAM_INFO.protoOrdinal();

    /** Number of parallel probes in a scatter-gather round. */
    private static final int SCATTER_FACTOR = 8;

    /** Threshold below which we switch from scatter-gather to sequential binary search. */
    private static final int SEQUENTIAL_THRESHOLD = 1000;

    /** Maximum exponential probe offset (2^20 ≈ 1M blocks). */
    private static final int MAX_EXPONENTIAL_POWER = 30;

    /** Thread pool for parallel probing operations. */
    private static final int PROBE_THREAD_POOL_SIZE = 16;

    private final String gcpBlockStreamDir;
    private final String billingProject;
    private final Path localProbeDir;
    private final BlockStore blockStore;

    /**
     * Lowest block number known to still exist in the bucket, refreshed lazily when a download fails. The source
     * bucket has a retention policy that continuously deletes the oldest blocks, so this value only grows. Any block
     * below it is considered evicted. {@code -1} until first refreshed.
     */
    private long firstAvailableBlock = -1;

    /**
     * Minimal view of the remote block store used by the resolver. Exists so the eviction handling can be tested
     * without GCS.
     */
    interface BlockStore {
        /** Downloads the given block into {@code localDir}; returns {@code false} on failure. */
        boolean download(long blockNumber, @NonNull Path localDir);

        /** Returns the lowest block number currently present, or {@code null} if it cannot be determined. */
        @Nullable
        Long firstBlock();

        /** Returns the highest block number currently present, or {@code null} if it cannot be determined. */
        @Nullable
        Long lastBlock();
    }

    /**
     * Rounds contained in a single block, or a marker that the block could not be inspected.
     *
     * @param evicted  {@code true} if the block file was removed from the bucket by the retention policy
     * @param minRound the smallest {@code RoundHeader.roundNumber} in the block, or {@code -1}
     * @param maxRound the largest {@code RoundHeader.roundNumber} in the block, or {@code -1}
     */
    private record BlockRounds(boolean evicted, long minRound, long maxRound) {
        static final BlockRounds EVICTED = new BlockRounds(true, -1, -1);
        static final BlockRounds UNKNOWN = new BlockRounds(false, -1, -1);

        boolean hasRounds() {
            return maxRound != -1;
        }
    }

    /**
     * Result of the block range resolution.
     *
     * @param leftBlock  the first block number to download (inclusive)
     * @param rightBlock the last block number to download (inclusive)
     */
    public record BlockRange(long leftBlock, long rightBlock) {
        public long fileCount() {
            return rightBlock - leftBlock + 1;
        }
    }

    /**
     * @param gcpBlockStreamDir GCS URI of the block stream directory
     * @param billingProject    optional billing project for requester-pays buckets
     * @param localProbeDir     local directory where probe files will be downloaded (also the
     *                          eventual target for bulk download)
     */
    public BlockRangeResolver(
            @NonNull final String gcpBlockStreamDir,
            @Nullable final String billingProject,
            @NonNull final Path localProbeDir) {
        this(gcpBlockStreamDir, billingProject, localProbeDir, new GcsBlockStore(gcpBlockStreamDir, billingProject));
    }

    BlockRangeResolver(
            @NonNull final String gcpBlockStreamDir,
            @Nullable final String billingProject,
            @NonNull final Path localProbeDir,
            @NonNull final BlockStore blockStore) {
        this.gcpBlockStreamDir = gcpBlockStreamDir;
        this.billingProject = billingProject;
        this.localProbeDir = localProbeDir;
        this.blockStore = blockStore;
    }

    /** {@link BlockStore} backed by the {@code gcloud storage} CLI. */
    private record GcsBlockStore(
            @NonNull String gcpBlockStreamDir, @Nullable String billingProject) implements BlockStore {
        @Override
        public boolean download(final long blockNumber, @NonNull final Path localDir) {
            return GcpPathHelper.downloadFile(blockFileUri(gcpBlockStreamDir, blockNumber), localDir, billingProject);
        }

        @Override
        @Nullable
        public Long firstBlock() {
            final String uri = listFirstFile(gcpBlockStreamDir, billingProject);
            return uri == null ? null : blockNumberFromUri(uri);
        }

        @Override
        @Nullable
        public Long lastBlock() {
            final String uri = listLastFile(gcpBlockStreamDir, billingProject);
            return uri == null ? null : blockNumberFromUri(uri);
        }
    }

    /**
     * Reads the {@link BlockStreamInfo} singleton from the given state and returns the block number
     * that represents the left boundary (i.e. {@code blockNumber + 1}).
     *
     * @param state the loaded binary state
     * @return the first block number that needs to be downloaded
     * @throws IllegalStateException if the singleton is not found or cannot be parsed
     */
    public static long extractLeftBoundary(@NonNull final BinaryState state) {
        final Bytes raw = state.getSingleton(BLOCK_STREAM_INFO_STATE_ID);
        if (raw == null) {
            throw new IllegalStateException(
                    "BlockStreamInfo singleton not found in state (state ID: " + BLOCK_STREAM_INFO_STATE_ID + ")");
        }
        try {
            final BlockStreamInfo bsi = BlockStreamInfo.PROTOBUF.parse(raw);
            final long blockNumber = bsi.blockNumber();
            log.info("BlockStreamInfo.blockNumber = {} → left boundary = {}", blockNumber, blockNumber + 1);
            return blockNumber + 1;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse BlockStreamInfo singleton", e);
        }
    }

    /**
     * Resolves the block range by round numbers rather than requiring a known left block number.
     * Used when no state is available to extract a starting block number from BlockStreamInfo.
     *
     * @param originRound the first round to include (left boundary)
     * @param targetRound the last round to include (right boundary)
     * @return the resolved block range
     */
    public BlockRange resolveByRounds(final long originRound, final long targetRound) throws IOException {
        return resolveByRounds(originRound, targetRound, 0L);
    }

    /**
     * As {@link #resolveByRounds(long, long)}, but extends the right boundary by {@code marginRounds} past the
     * target so the events that decide the target during replay are downloaded. The true {@code targetRound} is
     * still resolved strictly (an out-of-range target throws); only the margin extension clamps to the stream tip.
     */
    public BlockRange resolveByRounds(final long originRound, final long targetRound, final long marginRounds)
            throws IOException {
        log.info(CONSOLE, "Discovering block stream boundaries ...");

        log.info(CONSOLE, "  Listing first available block file ...");
        final Long firstListed = blockStore.firstBlock();

        // Note: listing the last file streams the whole directory and can take several minutes. Blocks at the
        // start of the range may be deleted by the bucket's retention policy in the meantime; the round search
        // below tolerates that (see probeBlockRounds).
        log.info(CONSOLE, "  Listing last available block file ...");
        final Long lastListed = blockStore.lastBlock();

        if (firstListed == null || lastListed == null) {
            throw new IOException("No block files found in " + gcpBlockStreamDir);
        }
        final long firstBlock = firstListed;
        final long lastBlock = lastListed;
        synchronized (this) {
            firstAvailableBlock = Math.max(firstAvailableBlock, firstBlock);
        }
        log.info(
                CONSOLE,
                "  Block stream range: [{}, {}] ({} blocks)",
                firstBlock,
                lastBlock,
                lastBlock - firstBlock + 1);

        final ExecutorService executor = Executors.newFixedThreadPool(PROBE_THREAD_POOL_SIZE);
        try {
            log.info(CONSOLE, "  Searching for block containing origin round {} ...", originRound);
            final long leftBlock = findBlockForTargetRound(firstBlock, lastBlock, originRound, executor);
            log.info(CONSOLE, "  Origin round {} found in block {}", originRound, leftBlock);

            // Strict: the real target must be present in the stream (guards a target chosen past the tip).
            log.info(CONSOLE, "  Searching for block containing target round {} ...", targetRound);
            final long targetBlock = findBlockForTargetRound(leftBlock, lastBlock, targetRound, executor);
            log.info(CONSOLE, "  Target round {} found in block {}", targetRound, targetBlock);

            final long rightBlock;
            if (marginRounds <= 0) {
                rightBlock = targetBlock;
            } else {
                final long marginRound = targetRound + marginRounds;
                log.info(
                        CONSOLE,
                        "  Extending right boundary to round {} (target + {} decision margin) ...",
                        marginRound,
                        marginRounds);
                rightBlock = findBlockForRoundClamped(targetBlock, lastBlock, marginRound, executor);
                log.info(CONSOLE, "  Right boundary resolved to block {}", rightBlock);
            }

            final BlockRange range = new BlockRange(leftBlock, rightBlock);
            cleanUpProbeFiles(range);
            return range;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Like {@link #findBlockForTargetRound} but, when {@code round} is past the stream tip, returns the last
     * available block instead of throwing. Used for the forward decision margin, which routinely asks for
     * {@code target + DECISION_MARGIN_ROUNDS} — a round that usually does not exist yet. Taking everything
     * available is correct: the surplus only supplies deciding events, and replay stops at the real target.
     */
    private long findBlockForRoundClamped(
            final long leftBlock, final long lastAvailableBlock, final long round, final ExecutorService executor)
            throws IOException {
        final long maxRoundInLastBlock = getBlockRounds(lastAvailableBlock).maxRound();
        if (maxRoundInLastBlock != -1 && round > maxRoundInLastBlock) {
            log.info(
                    CONSOLE,
                    "  Requested round {} is past the stream tip (last available round {}); clamping to last block {}",
                    round,
                    maxRoundInLastBlock,
                    lastAvailableBlock);
            return lastAvailableBlock;
        }
        return findBlockForTargetRound(leftBlock, lastAvailableBlock, round, executor);
    }

    private static long blockNumberFromUri(@NonNull final String fileUri) {
        // e.g. "gs://bucket/.../000000000000000000000000000070001488.blk.gz" → 70001488
        final String fileName = fileUri.substring(fileUri.lastIndexOf('/') + 1);
        final int dotIndex = fileName.indexOf('.');
        if (dotIndex <= 0) {
            throw new IllegalArgumentException("Cannot parse block number from: " + fileUri);
        }
        return Long.parseLong(fileName.substring(0, dotIndex));
    }

    /**
     * Resolves the full block range that must be downloaded to apply blocks up to
     * {@code targetRound}.
     *
     * @param leftBlock   the first block number to download (from {@link #extractLeftBoundary})
     * @param targetRound the target round to reach
     * @return the resolved block range
     * @throws IOException if probing or downloading fails
     */
    public BlockRange resolve(final long leftBlock, final long targetRound) throws IOException {
        log.info(
                "Resolving block range: leftBlock={}, targetRound={}, source={}",
                leftBlock,
                targetRound,
                gcpBlockStreamDir);
        log.info(CONSOLE, "Resolving block range (left block: {}, target round: {}) ...", leftBlock, targetRound);

        // Step 1: Verify the left boundary block exists
        if (!GcpPathHelper.fileExists(blockFileUri(gcpBlockStreamDir, leftBlock), billingProject)) {
            throw new IOException("Expected starting block file not found at "
                    + blockFileUri(gcpBlockStreamDir, leftBlock)
                    + ". The block stream may be incomplete or the state's BlockStreamInfo is inconsistent.");
        }

        // Shared executor for all parallel probing operations across phases 2 and 3
        final ExecutorService executor = Executors.newFixedThreadPool(PROBE_THREAD_POOL_SIZE);
        try {
            // Step 2: Find the extent of available blocks (last available block number)
            log.info(CONSOLE, "  Probing for last available block ...");
            final long lastAvailableBlock = findLastAvailableBlock(leftBlock, executor);
            log.info("Last available block in GCS: {}", lastAvailableBlock);
            log.info(CONSOLE, "  Last available block: {}", lastAvailableBlock);

            // Step 3: Find the block containing the target round via binary search
            log.info(CONSOLE, "  Searching for block containing target round {} ...", targetRound);
            final long rightBlock = findBlockForTargetRound(leftBlock, lastAvailableBlock, targetRound, executor);
            log.info("Block containing target round {}: {}", targetRound, rightBlock);

            final BlockRange range = new BlockRange(leftBlock, rightBlock);
            log.info(
                    "Resolved block range: [{}, {}] ({} files)",
                    range.leftBlock(),
                    range.rightBlock(),
                    range.fileCount());
            log.info(
                    CONSOLE,
                    "  Block range resolved: [{}, {}] ({} files)",
                    range.leftBlock(),
                    range.rightBlock(),
                    range.fileCount());

            // Clean up probe files outside the resolved range — if left in the directory,
            // the downstream consumer will interpret them as part of the expected block range.
            cleanUpProbeFiles(range);

            return range;
        } finally {
            executor.shutdownNow();
        }
    }

    // ========== Phase 2: Find last available block ==========

    /**
     * Uses scatter-gather exponential probing to find the last block number that exists in GCS.
     */
    private long findLastAvailableBlock(final long leftBlock, final ExecutorService executor) {
        log.info("Finding extent of available blocks starting from {} ...", leftBlock);

        // Phase 2a: Scatter exponential probes to find [lastHit, firstMiss] bracket
        final List<Long> probeOffsets = new ArrayList<>();
        for (int power = 0; power <= MAX_EXPONENTIAL_POWER; power++) {
            probeOffsets.add(1L << power); // 1, 2, 4, 8, ..., 2^20
        }

        // Check all exponential offsets in parallel
        final ConcurrentHashMap<Long, Boolean> existenceResults = new ConcurrentHashMap<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final long offset : probeOffsets) {
            final long blockNum = leftBlock + offset;
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        final boolean exists = GcpPathHelper.fileExistsNoRetry(
                                blockFileUri(gcpBlockStreamDir, blockNum), billingProject);
                        existenceResults.put(blockNum, exists);
                        log.debug("Probe block {}: {}", blockNum, exists ? "EXISTS" : "MISSING");
                    },
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Find the bracket [lastHit, firstMiss]
        long lastHit = leftBlock; // We already verified leftBlock exists
        long firstMiss = -1;

        for (final long offset : probeOffsets) {
            final long blockNum = leftBlock + offset;
            final Boolean exists = existenceResults.get(blockNum);
            if (exists != null && exists) {
                lastHit = Math.max(lastHit, blockNum);
            } else {
                if (firstMiss == -1 || blockNum < firstMiss) {
                    firstMiss = blockNum;
                }
            }
        }

        // If all probes exist (unlikely), the last available block is at least leftBlock + 2^MAX
        if (firstMiss == -1) {
            log.info(
                    "All exponential probes up to offset {} exist. Last available block is at least {}.",
                    1L << MAX_EXPONENTIAL_POWER,
                    lastHit);
            return lastHit;
        }

        // Phase 2b: Refine the exact last available block within [lastHit, firstMiss]
        return refineLastAvailableBlock(lastHit, firstMiss, executor);
    }

    /**
     * Scatter-gather refinement within the bracket to find the exact last existing block.
     */
    private long refineLastAvailableBlock(long lo, long hi, final ExecutorService executor) {
        while (hi - lo > 1) {
            final long range = hi - lo;
            if (range <= SEQUENTIAL_THRESHOLD) {
                // Sequential binary search for small ranges
                return sequentialBinarySearchExistence(lo, hi);
            }

            // Scatter probes evenly across the range
            final List<Long> probes = distributeProbes(lo, hi, SCATTER_FACTOR);
            final ConcurrentHashMap<Long, Boolean> results = parallelExistenceCheck(probes, executor);

            // Find new bracket
            long newLo = lo;
            long newHi = hi;
            for (final long probe : probes) {
                final Boolean exists = results.get(probe);
                if (exists != null && exists) {
                    newLo = Math.max(newLo, probe);
                } else {
                    newHi = Math.min(newHi, probe);
                }
            }
            lo = newLo;
            hi = newHi;
        }
        return lo;
    }

    /**
     * Simple sequential binary search for existence within a small range.
     */
    private long sequentialBinarySearchExistence(long lo, long hi) {
        while (hi - lo > 1) {
            final long mid = lo + (hi - lo) / 2;
            if (GcpPathHelper.fileExistsNoRetry(blockFileUri(gcpBlockStreamDir, mid), billingProject)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ========== Phase 3: Find block for target round ==========

    /**
     * Binary search (scatter-gather for large ranges, sequential for small) to find the block
     * whose round range includes {@code targetRound}.
     *
     * <p>The search downloads candidate block files, parses them to extract the maximum
     * {@code RoundHeader.roundNumber}, and narrows the range accordingly.</p>
     *
     * <p>The source bucket continuously deletes its oldest blocks (retention policy), so blocks near
     * {@code leftBlock} may disappear while the search runs. A block confirmed as evicted (it is below the
     * current first block of the bucket) only holds rounds older than anything still available, so it is
     * treated as "below the target" rather than as an error.</p>
     */
    private long findBlockForTargetRound(
            final long leftBlock, final long lastAvailableBlock, final long targetRound, final ExecutorService executor)
            throws IOException {
        log.info(
                "Searching for block containing target round {} in range [{}, {}]",
                targetRound,
                leftBlock,
                lastAvailableBlock);

        // First, check if the target round is reachable: download the last available block and check
        final BlockRounds lastRounds = getBlockRounds(lastAvailableBlock);
        final long maxRoundInLastBlock = lastRounds.maxRound();
        if (maxRoundInLastBlock == -1) {
            throw new IOException(
                    "Failed to parse any round headers from the last available block " + lastAvailableBlock);
        }
        if (targetRound > maxRoundInLastBlock) {
            throw new IOException(String.format(
                    "Target round %d exceeds the last available round %d in block %d at %s",
                    targetRound, maxRoundInLastBlock, lastAvailableBlock, gcpBlockStreamDir));
        }

        // Cache of block number → rounds (to avoid re-downloading)
        final ConcurrentHashMap<Long, BlockRounds> roundCache = new ConcurrentHashMap<>();
        roundCache.put(lastAvailableBlock, lastRounds);

        // Binary search invariant: the answer is in (lo, hi]; lo is below the target (or evicted).
        long lo;
        final long hi0 = lastAvailableBlock;

        // Check if the left boundary block already contains the target round
        final BlockRounds firstRounds = probeBlockRounds(leftBlock);
        roundCache.put(leftBlock, firstRounds);
        if (firstRounds.evicted()) {
            // The left boundary was deleted by the retention policy after it was discovered. Everything below the
            // current first available block is gone, so restart the search from there.
            final long firstAvailable = currentFirstAvailableBlock();
            log.warn(
                    "Block {} was removed from the bucket (retention policy) during the search; "
                            + "continuing from the first available block {}",
                    leftBlock,
                    firstAvailable);
            if (firstAvailable > hi0) {
                throw new IOException("All blocks up to " + hi0 + " were removed from " + gcpBlockStreamDir
                        + " while searching for round " + targetRound);
            }
            lo = firstAvailable - 1;
        } else {
            if (firstRounds.maxRound() >= targetRound) {
                return checkedResult(leftBlock, firstRounds, targetRound);
            }
            lo = leftBlock;
        }

        long hi = hi0;
        while (hi - lo > 1) {
            final long range = hi - lo;

            if (range <= SEQUENTIAL_THRESHOLD) {
                final long result = sequentialBinarySearchForRound(lo, hi, targetRound, roundCache);
                return checkedResult(result, roundCache.get(result), targetRound);
            }

            // Scatter-gather: probe SCATTER_FACTOR evenly spaced blocks
            final List<Long> probes = distributeProbes(lo, hi, SCATTER_FACTOR);
            log.debug("Scatter-gather round search: probing {} blocks in [{}, {}]", probes.size(), lo, hi);

            final ConcurrentHashMap<Long, BlockRounds> probeResults = parallelRoundCheck(probes, executor);
            roundCache.putAll(probeResults);

            // Find the narrowed bracket
            long newLo = lo;
            long newHi = hi;
            for (final long probe : probes) {
                final BlockRounds rounds = probeResults.get(probe);
                if (rounds == null) {
                    continue;
                }
                if (rounds.evicted()) {
                    // Removed by the retention policy: older than everything still available, hence below target
                    newLo = Math.max(newLo, probe);
                    continue;
                }
                if (!rounds.hasRounds()) {
                    // If we couldn't parse the block, treat it conservatively
                    continue;
                }
                final long maxRound = rounds.maxRound();
                if (maxRound < targetRound) {
                    newLo = Math.max(newLo, probe);
                } else {
                    // This block's max round >= targetRound; it could be the answer or earlier
                    newHi = Math.min(newHi, probe);
                }
            }

            // If no progress was made (all probes failed), fall back to sequential search
            if (newLo == lo && newHi == hi) {
                log.warn("Scatter-gather made no progress in [{}, {}]. Falling back to sequential search.", lo, hi);
                final long result = sequentialBinarySearchForRound(lo, hi, targetRound, roundCache);
                return checkedResult(result, roundCache.get(result), targetRound);
            }

            lo = newLo;
            hi = newHi;
        }

        // At this point, hi is the block whose max round >= targetRound
        return checkedResult(hi, roundCache.get(hi), targetRound);
    }

    /**
     * Returns {@code block} unchanged, logging a warning if it starts after {@code targetRound} — i.e. the target
     * round itself is no longer in the stream (typically because the retention policy already removed it).
     */
    private long checkedResult(final long block, @Nullable final BlockRounds rounds, final long targetRound) {
        if (rounds != null && rounds.hasRounds() && rounds.minRound() > targetRound) {
            log.warn(
                    CONSOLE,
                    "  Round {} is not in the available block stream: the closest block {} starts at round {}. "
                            + "Older blocks may have been removed by the bucket's retention policy.",
                    targetRound,
                    block,
                    rounds.minRound());
        }
        return block;
    }

    /**
     * Sequential binary search for the block containing the target round, for small ranges.
     */
    private long sequentialBinarySearchForRound(
            long lo, long hi, final long targetRound, final ConcurrentHashMap<Long, BlockRounds> roundCache) {
        while (hi - lo > 1) {
            final long mid = lo + (hi - lo) / 2;
            final BlockRounds rounds = roundCache.computeIfAbsent(mid, block -> {
                try {
                    return probeBlockRounds(block);
                } catch (IOException e) {
                    log.error("Failed to parse block {}", block, e);
                    return BlockRounds.UNKNOWN;
                }
            });

            if (rounds.evicted()) {
                // Removed by the retention policy: older than everything still available, hence below target
                lo = mid;
            } else if (!rounds.hasRounds()) {
                // If we can't parse, try to narrow from the other side
                hi = mid;
            } else if (rounds.maxRound() < targetRound) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    // ========== Block parsing ==========

    /**
     * Like {@link #getBlockRounds}, but returns {@link BlockRounds#EVICTED} instead of failing when the block can
     * no longer be downloaded because the bucket's retention policy removed it. Eviction is confirmed by re-listing
     * the first available block; a download failure for a block at or above it is still reported as an error.
     */
    @NonNull
    private BlockRounds probeBlockRounds(final long blockNumber) throws IOException {
        try {
            return getBlockRounds(blockNumber);
        } catch (BlockDownloadException e) {
            if (isEvicted(blockNumber)) {
                log.info("Block {} has been removed from the bucket by the retention policy", blockNumber);
                return BlockRounds.EVICTED;
            }
            throw e;
        }
    }

    /**
     * Returns {@code true} if {@code blockNumber} is below the first block currently present in the bucket.
     * Retention only ever removes the oldest blocks, so the cached watermark is only refreshed when it cannot
     * already prove eviction.
     */
    private synchronized boolean isEvicted(final long blockNumber) {
        if (blockNumber < firstAvailableBlock) {
            return true;
        }
        final Long first = blockStore.firstBlock();
        if (first != null) {
            firstAvailableBlock = Math.max(firstAvailableBlock, first);
        }
        return blockNumber < firstAvailableBlock;
    }

    /** Returns the first block currently present in the bucket, refreshing the cached value. */
    private synchronized long currentFirstAvailableBlock() {
        final Long first = blockStore.firstBlock();
        if (first != null) {
            firstAvailableBlock = Math.max(firstAvailableBlock, first);
        }
        return firstAvailableBlock;
    }

    /** Signals that a block file could not be downloaded (it may or may not still exist). */
    private static final class BlockDownloadException extends IOException {
        BlockDownloadException(@NonNull final String message) {
            super(message);
        }
    }

    /**
     * Downloads a single block file (if not already in the local probe directory),
     * parses it, and returns the range of round numbers found in its {@code RoundHeader} items.
     *
     * @param blockNumber the block number to inspect
     * @return the block's rounds; {@code minRound}/{@code maxRound} are {@code -1} if no round headers were found
     * @throws BlockDownloadException if the file could not be downloaded
     * @throws IOException            if the file could not be parsed
     */
    @NonNull
    private BlockRounds getBlockRounds(final long blockNumber) throws IOException {
        final String fileName = blockFileName(blockNumber);
        final Path localFile = localProbeDir.resolve(fileName);

        // Download if not already cached locally
        if (!localFile.toFile().exists() || localFile.toFile().length() == 0) {
            if (!blockStore.download(blockNumber, localProbeDir)) {
                throw new BlockDownloadException(
                        "Failed to download block file: " + blockFileUri(gcpBlockStreamDir, blockNumber));
            }
        }

        // Parse the block and extract min/max round
        try {
            final Block block = BlockStreamAccess.blockFrom(localFile);
            long minRound = Long.MAX_VALUE;
            long maxRound = -1;
            for (final BlockItem item : block.items()) {
                if (item.hasRoundHeader()) {
                    final long round = item.roundHeader().roundNumber();
                    minRound = Math.min(minRound, round);
                    maxRound = Math.max(maxRound, round);
                }
            }
            log.debug("Block {} → rounds [{}, {}]", blockNumber, minRound, maxRound);
            return maxRound == -1 ? BlockRounds.UNKNOWN : new BlockRounds(false, minRound, maxRound);
        } catch (Exception e) {
            throw new IOException("Failed to parse block file: " + localFile, e);
        }
    }

    /**
     * Removes any {@code .blk.gz} files from the local probe directory whose block number falls
     * outside the resolved range. These are artifacts from the binary search probing phase.
     */
    private void cleanUpProbeFiles(@NonNull final BlockRange range) {
        try (var stream = Files.list(localProbeDir)) {
            int removed = 0;
            for (final Path file : stream.toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".blk.gz")) {
                    continue;
                }
                try {
                    final long blockNum = Long.parseLong(name.substring(0, name.indexOf('.')));
                    if (blockNum < range.leftBlock() || blockNum > range.rightBlock()) {
                        Files.delete(file);
                        removed++;
                    }
                } catch (NumberFormatException e) {
                    // Not a standard block file name — leave it alone
                }
            }
            if (removed > 0) {
                log.info(
                        "Removed {} probe files outside resolved range [{}, {}]",
                        removed,
                        range.leftBlock(),
                        range.rightBlock());
            }
        } catch (IOException e) {
            log.warn("Failed to clean up probe files in {}", localProbeDir, e);
        }
    }

    // ========== Parallel utilities ==========

    /**
     * Distributes {@code count} probe points evenly within the open interval {@code (lo, hi)}.
     */
    @NonNull
    private static List<Long> distributeProbes(final long lo, final long hi, final int count) {
        final long range = hi - lo;
        if (range <= 1) {
            return Collections.emptyList();
        }
        final List<Long> probes = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            final long probe = lo + (range * i) / (count + 1);
            if (probe > lo && probe < hi) {
                probes.add(probe);
            }
        }
        return probes;
    }

    /**
     * Checks existence of multiple block files in parallel using the shared executor.
     */
    @NonNull
    private ConcurrentHashMap<Long, Boolean> parallelExistenceCheck(
            @NonNull final List<Long> blockNumbers, @NonNull final ExecutorService executor) {
        final ConcurrentHashMap<Long, Boolean> results = new ConcurrentHashMap<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final long blockNum : blockNumbers) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        results.put(
                                blockNum,
                                GcpPathHelper.fileExistsNoRetry(
                                        blockFileUri(gcpBlockStreamDir, blockNum), billingProject));
                    },
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return results;
    }

    /**
     * Downloads and parses multiple block files in parallel using the shared executor,
     * returning block → rounds mappings.
     */
    @NonNull
    private ConcurrentHashMap<Long, BlockRounds> parallelRoundCheck(
            @NonNull final List<Long> blockNumbers, @NonNull final ExecutorService executor) {
        final ConcurrentHashMap<Long, BlockRounds> results = new ConcurrentHashMap<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final long blockNum : blockNumbers) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            results.put(blockNum, probeBlockRounds(blockNum));
                        } catch (IOException e) {
                            log.warn("Failed to get rounds for block {}: {}", blockNum, e.getMessage());
                            results.put(blockNum, BlockRounds.UNKNOWN);
                        }
                    },
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return results;
    }
}
