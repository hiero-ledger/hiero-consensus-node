// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.gcp.GcpPathHelper.CONSOLE;
import static com.hedera.statevalidation.util.ConfigUtils.PARALLELISM;
import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;

/**
 * Reconstructs events from a directory of block stream files and writes them as PCES files.
 *
 * <p>The pipeline has three stages:
 * <ol>
 *   <li><b>Read</b> — a single producer thread reads blocks from disk in ascending block-number
 *       order and submits a reconstruction task per block.</li>
 *   <li><b>Reconstruct (parallel)</b> — a pool of worker threads reconstructs each block's events
 *       (transaction parsing and event hashing — the CPU-bound work) into an ordered list. Each
 *       worker uses its own {@link BlockStreamEventBuilder} (one per pool thread via a
 *       {@link ThreadLocal}); a builder is never shared across threads.</li>
 *   <li><b>Write</b> — a single consumer thread takes completed blocks <i>in submission (block)
 *       order</i> and writes their events to the {@link CommonPcesWriter}. Writing must remain
 *       single-threaded and ordered: the writer derives each file descriptor from the previous one,
 *       and PCES files must contain events in stream order.</li>
 * </ol>
 *
 * <p>Block-level parallelism is valid because each block's reconstruction is self-contained:
 * in-block parent references resolve within the block, and cross-block parents use
 * {@code EventDescriptor}s carried directly in the stream — no block depends on another block's
 * reconstructed events.
 *
 * <p>A semaphore bounds the number of in-flight (submitted but not yet written) blocks, so memory
 * stays bounded regardless of the extraction-window size.
 *
 * <p><b>Limitations.</b> Redacted or filtered block streams are not supported (a redacted
 * transaction has no bytes to replay).
 */
public final class BlocksToPcesWorkflow {

    private static final Logger log = LogManager.getLogger(BlocksToPcesWorkflow.class);

    /**
     * How far the PCES non-ancient boundary lags behind the highest birth round seen so far. See
     * {@link #advanceBoundary}. Far larger than the platform's non-ancient window (default 26) to
     * absorb the natural backward jitter of birth rounds in the block stream.
     */
    private static final long BOUNDARY_LAG_ROUNDS = 10_000;

    /** Max number of in-flight (submitted but not yet written) blocks — bounds memory. */
    private static final int MAX_IN_FLIGHT_BLOCKS = 512;

    /** One reconstruction builder per worker thread; never shared across threads. */
    private static final ThreadLocal<BlockStreamEventBuilder> BUILDERS =
            ThreadLocal.withInitial(BlockStreamEventBuilder::new);

    /** Identity sentinel marking the end of the ordered Future stream. */
    private static final Future<List<PlatformEvent>> POISON =
            java.util.concurrent.CompletableFuture.completedFuture(List.of());

    private BlocksToPcesWorkflow() {}

    /**
     * Reads block files from {@code blockStreamDirectory}, reconstructs events in parallel, and
     * writes them to PCES files under {@code pcesOutputDir} in block order.
     *
     * @param blockStreamDirectory directory containing the {@code .blk.gz} files (must be contiguous)
     * @param pcesOutputDir directory where the PCES database tree is created (must be empty/new)
     * @param originRound the starting round of the state snapshot the PCES files will be replayed
     *     against; stamped as the PCES stream origin
     * @param leftRound inclusive lower bound (consensus round) of the extraction window; blocks whose
     *     max round is below this are excluded
     * @param targetRound inclusive upper bound (consensus round) of the extraction window; blocks whose
     *     min round is above this are excluded
     * @return the number of events written
     * @throws IOException if reading blocks or writing PCES files fails
     */
    public static long convert(
            @NonNull final Path blockStreamDirectory,
            @NonNull final Path pcesOutputDir,
            final long originRound,
            final long leftRound,
            final long targetRound)
            throws IOException {
        requireNonNull(blockStreamDirectory);
        requireNonNull(pcesOutputDir);

        // Discover and order block file paths up front (also validates contiguity), then restrict to the
        // [leftRound, targetRound] window using the same whole-block, max-RoundHeader.roundNumber semantics
        // the GCS BlockRangeResolver uses — so local and GCS paths produce the identical block set. The
        // expensive per-block work (gzip decode + protobuf parse + event reconstruction) is deferred to the
        // worker pool — see readAndSubmit — so it runs in parallel rather than on the reader thread.
        final List<Path> allFiles = orderedBlockFiles(blockStreamDirectory);
        if (allFiles.isEmpty()) {
            throw new IllegalArgumentException("No block files found in " + blockStreamDirectory);
        }
        final List<Path> orderedFiles = filterByRoundWindow(allFiles, leftRound, targetRound);
        if (orderedFiles.isEmpty()) {
            throw new IllegalArgumentException("No block files fall within round window [" + leftRound + ", "
                    + targetRound + "] in " + blockStreamDirectory);
        }

        final int workers = Math.max(1, PARALLELISM);
        log.info(
                CONSOLE,
                "Reconstructing events from {} blocks (of {} present, round window [{}, {}]) using {} worker thread(s)",
                orderedFiles.size(),
                allFiles.size(),
                leftRound,
                targetRound,
                workers);

        final CommonPcesWriter writer = createPcesWriter(pcesOutputDir, originRound);

        final ExecutorService pool = Executors.newFixedThreadPool(workers);
        // Ordered hand-off of reconstruction Futures; capacity covers the in-flight window + poison.
        final BlockingQueue<Future<List<PlatformEvent>>> ordered = new ArrayBlockingQueue<>(MAX_IN_FLIGHT_BLOCKS + 1);
        final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT_BLOCKS);
        final AtomicReference<Throwable> readError = new AtomicReference<>();

        final Thread reader = new Thread(
                () -> readAndSubmit(orderedFiles, pool, ordered, inFlight, readError), "blocks-to-pces-reader");
        reader.setDaemon(true);
        reader.start();

        final long eventCount;
        try {
            eventCount = writeInOrder(ordered, inFlight, readError, writer);
        } finally {
            pool.shutdownNow();
        }

        writer.syncCurrentFile();
        writer.closeCurrentMutableFile();

        log.info("Wrote {} event(s) to PCES files under {}", eventCount, pcesOutputDir);
        return eventCount;
    }

    /**
     * Submits one decode+reconstruction task per block <i>path</i>, in order. Each task does the
     * heavy work — {@code BlockStreamAccess.blockFrom(path)} (gzip decode + protobuf parse) and
     * event reconstruction — on a worker thread, so all of it runs in parallel. The reader thread
     * itself only enumerates paths and submits, which is cheap. The resulting {@link Future} is
     * enqueued in block order so the consumer can write results sequentially. A semaphore bounds
     * how far the reader runs ahead of the consumer.
     */
    private static void readAndSubmit(
            @NonNull final List<Path> orderedFiles,
            @NonNull final ExecutorService pool,
            @NonNull final BlockingQueue<Future<List<PlatformEvent>>> ordered,
            @NonNull final Semaphore inFlight,
            @NonNull final AtomicReference<Throwable> readError) {
        try {
            for (final Path file : orderedFiles) {
                inFlight.acquire(); // backpressure: block if too many blocks are in flight

                final Callable<List<PlatformEvent>> task = () -> {
                    final Block block = BlockStreamAccess.blockFrom(file); // decode happens here, in the worker
                    final List<PlatformEvent> events = new ArrayList<>();
                    BUILDERS.get().processBlock(block, events::add);
                    return events;
                };
                ordered.put(pool.submit(task));
            }
            ordered.put(POISON);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Throwable t) {
            readError.compareAndSet(null, t);
            try {
                ordered.put(POISON);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Consumes reconstructed blocks in order (by taking Futures in submission order), writing each
     * block's events to the PCES writer on this single thread.
     */
    private static long writeInOrder(
            @NonNull final BlockingQueue<Future<List<PlatformEvent>>> ordered,
            @NonNull final Semaphore inFlight,
            @NonNull final AtomicReference<Throwable> readError,
            @NonNull final CommonPcesWriter writer)
            throws IOException {

        final AtomicLong eventCount = new AtomicLong(0);
        final AtomicLong maxBirthRound = new AtomicLong(0);
        final AtomicLong currentBoundary = new AtomicLong(-1);
        long blockCount = 0;

        while (true) {
            if (readError.get() != null) {
                throw new IOException("Block read/submit failed", readError.get());
            }
            final Future<List<PlatformEvent>> future;
            try {
                future = ordered.take();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while consuming reconstructed blocks", e);
            }
            if (future == POISON) {
                break;
            }

            final List<PlatformEvent> events;
            try {
                events = future.get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while awaiting block reconstruction", e);
            } catch (final ExecutionException e) {
                throw new IOException("Block reconstruction failed", e.getCause());
            } finally {
                inFlight.release(); // free a slot as soon as the block leaves the in-flight window
            }

            for (final PlatformEvent event : events) {
                advanceBoundary(writer, event.getBirthRound(), maxBirthRound, currentBoundary);
                writer.prepareOutputStream(event);
                writer.getCurrentMutableFile().writeEvent(event);
                eventCount.incrementAndGet();
            }

            blockCount++;
            if (blockCount % 5_000 == 0) {
                log.info(CONSOLE, "Processed {} blocks, {} events so far ...", blockCount, eventCount.get());
            }
        }

        if (readError.get() != null) {
            throw new IOException("Block read/submit failed", readError.get());
        }
        return eventCount.get();
    }

    /**
     * Advances the writer's non-ancient boundary so newly created PCES files get a high enough upper
     * bound to hold the events being written, without rotating on every event. Lags the highest
     * birth round seen by {@link #BOUNDARY_LAG_ROUNDS} (floored at {@code ROUND_FIRST}); only ever
     * increases (the writer rejects a decrease). Deliberately not floored at {@code originRound},
     * since events near the window start can have birth rounds slightly below it.
     */
    private static void advanceBoundary(
            @NonNull final CommonPcesWriter writer,
            final long birthRound,
            @NonNull final AtomicLong maxBirthRound,
            @NonNull final AtomicLong currentBoundary) {
        if (birthRound > maxBirthRound.get()) {
            maxBirthRound.set(birthRound);
        }
        final long desiredBoundary = Math.max(1L, maxBirthRound.get() - BOUNDARY_LAG_ROUNDS);
        if (desiredBoundary > currentBoundary.get()) {
            // Only the ancientThreshold is consulted by the PCES writer; the other EventWindow
            // fields are set to consistent, valid values (>= ROUND_FIRST).
            writer.updateNonAncientEventBoundary(
                    new EventWindow(desiredBoundary, desiredBoundary, desiredBoundary, desiredBoundary));
            currentBoundary.set(desiredBoundary);
        }
    }

    private static CommonPcesWriter createPcesWriter(@NonNull final Path pcesOutputDir, final long originRound)
            throws IOException {
        Files.createDirectories(pcesOutputDir);
        final PlatformContext platformContext = getPlatformContext();
        final Configuration configuration = platformContext.getConfiguration();

        final PcesFileManager fileManager = new PcesFileManager(
                configuration,
                platformContext.getMetrics(),
                platformContext.getTime(),
                new PcesFileTracker(),
                pcesOutputDir,
                originRound);

        final CommonPcesWriter writer = new CommonPcesWriter(configuration, fileManager);
        writer.beginStreamingNewEvents();
        return writer;
    }

    /**
     * Restricts the ordered block files to those overlapping the consensus-round window
     * {@code [leftRound, targetRound]}, plus <b>one extra block</b> beyond the last in-window block.
     *
     * <p>The window selection uses whole-block granularity identical to the GCS
     * {@code BlockRangeResolver}: a block's round span is taken from its {@code RoundHeader.roundNumber}
     * items (min and max), and a block is included if its span intersects {@code [leftRound, targetRound]}
     * (i.e. {@code maxRound >= leftRound && minRound <= targetRound}).
     *
     * <p>The one extra trailing block is required so that {@code replay-pces} can <i>close</i> the block
     * containing the target round. Consensus on a round depends on events with higher <b>birth rounds</b>
     * than that round's <b>consensus round</b> (later events vote to decide earlier rounds' fame). Those
     * deciding events live in the next block. Without it, replay reaches consensus a few rounds short of
     * the target block's final round, so the platform's {@code endRound} never hits the block-closing
     * boundary and the last block is left open (0-byte, no {@code .mf} marker). Including the next whole
     * block guarantees the deciding events are present and the target block closes. Blocks beyond the
     * target block need not themselves close — they only supply the deciding events.
     */
    private static List<Path> filterByRoundWindow(
            @NonNull final List<Path> orderedFiles, final long leftRound, final long targetRound) {
        int lastInWindow = -1;
        final List<Path> selected = new ArrayList<>();
        for (int i = 0; i < orderedFiles.size(); i++) {
            final Path file = orderedFiles.get(i);
            final long[] span = roundSpan(file);
            final long minRound = span[0];
            final long maxRound = span[1];
            if (minRound == -1) {
                continue; // no round headers — not a round-bearing block
            }
            // Include if [minRound, maxRound] intersects [leftRound, targetRound].
            if (maxRound >= leftRound && minRound <= targetRound) {
                selected.add(file);
                lastInWindow = i;
            }
        }

        // Append one extra block immediately after the last in-window block, if present. Its events
        // are what let the hashgraph decide consensus on the target block's final rounds, so the
        // target block closes during replay. orderedBlockFiles guarantees contiguity, so index+1 is
        // the next sequential block.
        if (lastInWindow >= 0 && lastInWindow + 1 < orderedFiles.size()) {
            selected.add(orderedFiles.get(lastInWindow + 1));
        }

        return selected;
    }

    /**
     * Returns {@code [minRound, maxRound]} from a block's {@code RoundHeader} items, or {@code [-1, -1]} if
     * the block has none. Mirrors {@code BlockRangeResolver}'s round extraction.
     */
    private static long[] roundSpan(@NonNull final Path file) {
        final Block block = BlockStreamAccess.blockFrom(file);
        long minRound = -1;
        long maxRound = -1;
        for (final var item : block.items()) {
            if (item.hasRoundHeader()) {
                final long r = item.roundHeader().roundNumber();
                if (minRound == -1 || r < minRound) {
                    minRound = r;
                }
                if (r > maxRound) {
                    maxRound = r;
                }
            }
        }
        return new long[]{minRound, maxRound};
    }

    /**
     * Discovers all block files under the directory, validates that they form a contiguous sequence
     * with no gaps, and returns their paths sorted by block number. The walk, extraction, and gap
     * scan run in parallel; the gap check is an order-independent scan over adjacent indices.
     *
     * @return block file paths sorted ascending by block number
     */
    private static List<Path> orderedBlockFiles(@NonNull final Path blockStreamDirectory) throws IOException {
        record NumberedPath(long blockNumber, Path path) {}

        final List<NumberedPath> numbered;
        try (var stream = Files.walk(blockStreamDirectory)) {
            numbered = stream.parallel()
                    .filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .map(p -> new NumberedPath(BlockStreamAccess.extractBlockNumber(p), p))
                    .filter(np -> np.blockNumber() != -1)
                    .sorted(java.util.Comparator.comparingLong(NumberedPath::blockNumber))
                    .toList();
        }

        if (numbered.isEmpty()) {
            return List.of();
        }

        // Parallel, order-independent contiguity check over adjacent indices.
        IntStream.range(1, numbered.size()).parallel().forEach(i -> {
            final long prev = numbered.get(i - 1).blockNumber();
            final long curr = numbered.get(i).blockNumber();
            if (curr != prev + 1) {
                throw new RuntimeException(
                        "Block stream is missing " + (curr - prev - 1) + " block(s) between " + prev + " and " + curr);
            }
        });

        log.info(
                "Block file contiguity validated: {} block files present, range [{}, {}]",
                numbered.size(),
                numbered.getFirst().blockNumber(),
                numbered.getLast().blockNumber());

        return numbered.stream().map(NumberedPath::path).toList();
    }
}
