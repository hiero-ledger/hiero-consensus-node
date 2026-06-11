// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;

/**
 * Reconstructs events from a directory of block stream files and writes them as PCES files,
 * streaming one block at a time.
 *
 * <p>This produces an unsigned preconsensus event stream from the block
 * stream. The resulting files can be replayed on a node started from a state snapshot at the
 * {@code originRound} to reproduce the original block stream (subject to the unsigned-event intake
 * path being available on the replaying node — out of scope for this tool).
 *
 * <p>Event reconstruction is delegated to {@link BlockStreamEventBuilder}, which processes one
 * block at a time and delivers each completed event via a callback. Each event is written to the
 * PCES stream immediately, so memory usage is bounded by the largest single block rather than the
 * entire extraction window. This makes it feasible to process very large windows (months of data).
 *
 * <p><b>Limitations.</b> Redacted or filtered block streams are not supported (a redacted
 * transaction has no bytes to replay).
 */
public final class BlocksToPcesWorkflow {

    private static final Logger log = LogManager.getLogger(BlocksToPcesWorkflow.class);

    private BlocksToPcesWorkflow() {}

    /**
     * Reads block files from {@code blockStreamDirectory}, reconstructs events one block at a time,
     * and writes them to PCES files under {@code pcesOutputDir}.
     *
     * @param blockStreamDirectory directory containing the {@code .blk.gz} files (must be contiguous)
     * @param pcesOutputDir directory where the PCES database tree is created (must be empty/new)
     * @param originRound the starting round of the state snapshot the PCES files will be replayed
     *     against; stamped as the PCES stream origin
     * @return the number of events written
     * @throws IOException if reading blocks or writing PCES files fails
     */
    public static long convert(
            @NonNull final Path blockStreamDirectory, @NonNull final Path pcesOutputDir, final long originRound)
            throws IOException {
        requireNonNull(blockStreamDirectory);
        requireNonNull(pcesOutputDir);

        validateNoMissingBlocks(blockStreamDirectory);

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
        // Without this, the writer assumes every event is already durable and writes nothing.
        writer.beginStreamingNewEvents();

        final AtomicLong eventCount = new AtomicLong(0);
        final AtomicLong blockCount = new AtomicLong(0);
        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder();

        try (final Stream<com.hedera.hapi.block.stream.Block> blockStream =
                BlockStreamAccess.readBlocks(blockStreamDirectory, false)) {
            blockStream.forEach(block -> {
                eventBuilder.processBlock(block, event -> {
                    try {
                        writer.prepareOutputStream(event);
                        writer.getCurrentMutableFile().writeEvent(event);
                        eventCount.incrementAndGet();
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to write event to PCES file", e);
                    }
                });
                final long count = blockCount.incrementAndGet();
                if (count % 10_000 == 0) {
                    log.info("Processed {} blocks, {} events so far ...", count, eventCount.get());
                }
            });
        }

        writer.syncCurrentFile();
        writer.closeCurrentMutableFile();

        log.info(
                "Wrote {} event(s) from {} block(s) to PCES files under {}",
                eventCount.get(),
                blockCount.get(),
                pcesOutputDir);
        return eventCount.get();
    }

    /**
     * Validates that the block files form a contiguous sequence with no gaps, providing an early,
     * descriptive failure rather than a confusing parent-resolution error during reconstruction.
     */
    private static void validateNoMissingBlocks(@NonNull final Path blockStreamDirectory) throws IOException {
        final AtomicLong previousBlock = new AtomicLong(-1);
        final AtomicLong blockCount = new AtomicLong(0);
        final AtomicLong firstBlock = new AtomicLong(-1);
        final AtomicLong lastBlock = new AtomicLong(-1);

        try (var stream = Files.walk(blockStreamDirectory)) {
            stream.filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .map(BlockStreamAccess::extractBlockNumber)
                    .filter(n -> n != -1)
                    .sorted()
                    .forEach(blockNumber -> {
                        final long prev = previousBlock.getAndSet(blockNumber);
                        blockCount.incrementAndGet();
                        if (firstBlock.get() == -1) {
                            firstBlock.set(blockNumber);
                        }
                        lastBlock.set(blockNumber);

                        if (prev != -1 && blockNumber != prev + 1) {
                            final long gap = blockNumber - prev - 1;
                            throw new RuntimeException("Block stream is missing " + gap + " block(s) between " + prev
                                    + " and " + blockNumber);
                        }
                    });
        }

        if (blockCount.get() > 0) {
            log.info(
                    "Block file contiguity validated: {} block files present, range [{}, {}]",
                    blockCount.get(),
                    firstBlock.get(),
                    lastBlock.get());
        }
    }
}
