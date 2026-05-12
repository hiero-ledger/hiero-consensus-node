// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.gcp;

import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileName;
import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileUri;

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

    /** The state ID for the BlockStreamInfo singleton. */
    private static final int BLOCK_STREAM_INFO_STATE_ID =
            SingletonType.BLOCKSTREAMSERVICE_I_BLOCK_STREAM_INFO.protoOrdinal();

    /** Number of parallel probes in a scatter-gather round. */
    private static final int SCATTER_FACTOR = 8;

    /** Threshold below which we switch from scatter-gather to sequential binary search. */
    private static final int SEQUENTIAL_THRESHOLD = 1000;

    /** Maximum exponential probe offset (2^20 ≈ 1M blocks). */
    private static final int MAX_EXPONENTIAL_POWER = 20;

    /** Thread pool for parallel probing operations. */
    private static final int PROBE_THREAD_POOL_SIZE = 16;

    private final String gcpBlockStreamDir;
    private final String billingProject;
    private final Path localProbeDir;

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
        this.gcpBlockStreamDir = gcpBlockStreamDir;
        this.billingProject = billingProject;
        this.localProbeDir = localProbeDir;
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
        System.out.printf("Resolving block range (left block: %d, target round: %d) ...%n", leftBlock, targetRound);

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
            System.out.printf("  Probing for last available block ...%n");
            final long lastAvailableBlock = findLastAvailableBlock(leftBlock, executor);
            log.info("Last available block in GCS: {}", lastAvailableBlock);
            System.out.printf("  Last available block: %d%n", lastAvailableBlock);

            // Step 3: Find the block containing the target round via binary search
            System.out.printf("  Searching for block containing target round %d ...%n", targetRound);
            final long rightBlock = findBlockForTargetRound(leftBlock, lastAvailableBlock, targetRound, executor);
            log.info("Block containing target round {}: {}", targetRound, rightBlock);

            final BlockRange range = new BlockRange(leftBlock, rightBlock);
            log.info(
                    "Resolved block range: [{}, {}] ({} files)",
                    range.leftBlock(),
                    range.rightBlock(),
                    range.fileCount());
            System.out.printf(
                    "  Block range resolved: [%d, %d] (%d files)%n",
                    range.leftBlock(), range.rightBlock(), range.fileCount());
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
            log.warn(
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
        final long maxRoundInLastBlock = getMaxRound(lastAvailableBlock);
        if (maxRoundInLastBlock == -1) {
            throw new IOException(
                    "Failed to parse any round headers from the last available block " + lastAvailableBlock);
        }
        if (targetRound > maxRoundInLastBlock) {
            throw new IOException(String.format(
                    "Target round %d exceeds the last available round %d in block %d at %s",
                    targetRound, maxRoundInLastBlock, lastAvailableBlock, gcpBlockStreamDir));
        }

        // Check if the left boundary block already contains the target round
        final long maxRoundInFirstBlock = getMaxRound(leftBlock);
        if (maxRoundInFirstBlock >= targetRound) {
            return leftBlock;
        }

        // Binary search between leftBlock and lastAvailableBlock
        long lo = leftBlock;
        long hi = lastAvailableBlock;

        // Cache of block number → max round (to avoid re-downloading)
        final ConcurrentHashMap<Long, Long> roundCache = new ConcurrentHashMap<>();
        roundCache.put(leftBlock, maxRoundInFirstBlock);
        roundCache.put(lastAvailableBlock, maxRoundInLastBlock);

        while (hi - lo > 1) {
            final long range = hi - lo;

            if (range <= SEQUENTIAL_THRESHOLD) {
                return sequentialBinarySearchForRound(lo, hi, targetRound, roundCache);
            }

            // Scatter-gather: probe SCATTER_FACTOR evenly spaced blocks
            final List<Long> probes = distributeProbes(lo, hi, SCATTER_FACTOR);
            log.debug("Scatter-gather round search: probing {} blocks in [{}, {}]", probes.size(), lo, hi);

            final ConcurrentHashMap<Long, Long> probeResults = parallelRoundCheck(probes, executor);
            roundCache.putAll(probeResults);

            // Find the narrowed bracket
            long newLo = lo;
            long newHi = hi;
            for (final long probe : probes) {
                final Long maxRound = probeResults.get(probe);
                if (maxRound == null || maxRound == -1) {
                    // If we couldn't parse the block, treat it conservatively
                    continue;
                }
                if (maxRound < targetRound) {
                    newLo = Math.max(newLo, probe);
                } else {
                    // This block's max round >= targetRound; it could be the answer or earlier
                    newHi = Math.min(newHi, probe);
                }
            }
            lo = newLo;
            hi = newHi;
        }

        // At this point, hi is the block whose max round >= targetRound
        return hi;
    }

    /**
     * Sequential binary search for the block containing the target round, for small ranges.
     */
    private long sequentialBinarySearchForRound(
            long lo, long hi, final long targetRound, final ConcurrentHashMap<Long, Long> roundCache)
            throws IOException {
        while (hi - lo > 1) {
            final long mid = lo + (hi - lo) / 2;
            final long maxRound = roundCache.computeIfAbsent(mid, block -> {
                try {
                    return getMaxRound(block);
                } catch (IOException e) {
                    log.error("Failed to parse block {}", block, e);
                    return -1L;
                }
            });

            if (maxRound == -1) {
                // If we can't parse, try to narrow from the other side
                hi = mid;
            } else if (maxRound < targetRound) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    // ========== Block parsing ==========

    /**
     * Downloads a single block file (if not already in the local probe directory),
     * parses it, and returns the maximum round number found in its {@code RoundHeader} items.
     *
     * @param blockNumber the block number to inspect
     * @return the maximum round number, or {@code -1} if no round headers were found
     */
    private long getMaxRound(final long blockNumber) throws IOException {
        final String fileName = blockFileName(blockNumber);
        final Path localFile = localProbeDir.resolve(fileName);

        // Download if not already cached locally
        if (!localFile.toFile().exists() || localFile.toFile().length() == 0) {
            final String uri = blockFileUri(gcpBlockStreamDir, blockNumber);
            final boolean ok = GcpPathHelper.downloadFile(uri, localProbeDir, billingProject);
            if (!ok) {
                throw new IOException("Failed to download block file: " + uri);
            }
        }

        // Parse the block and extract max round
        try {
            final Block block = BlockStreamAccess.blockFrom(localFile);
            long maxRound = -1;
            for (final BlockItem item : block.items()) {
                if (item.hasRoundHeader()) {
                    maxRound = Math.max(maxRound, item.roundHeader().roundNumber());
                }
            }
            log.debug("Block {} → maxRound={}", blockNumber, maxRound);
            return maxRound;
        } catch (Exception e) {
            throw new IOException("Failed to parse block file: " + localFile, e);
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
     * returning block → maxRound mappings.
     */
    @NonNull
    private ConcurrentHashMap<Long, Long> parallelRoundCheck(
            @NonNull final List<Long> blockNumbers, @NonNull final ExecutorService executor) {
        final ConcurrentHashMap<Long, Long> results = new ConcurrentHashMap<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final long blockNum : blockNumbers) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            results.put(blockNum, getMaxRound(blockNum));
                        } catch (IOException e) {
                            log.warn("Failed to get max round for block {}: {}", blockNum, e.getMessage());
                            results.put(blockNum, -1L);
                        }
                    },
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return results;
    }
}
