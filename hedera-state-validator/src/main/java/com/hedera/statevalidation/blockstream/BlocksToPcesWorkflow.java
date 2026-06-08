// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;

/**
 * Reconstructs events from a directory of block stream files and writes them as PCES files.
 *
 * <p>This produces an unsigned, consensus-equivalent preconsensus event stream from the block
 * stream. The resulting files can be replayed on a node started from a state snapshot at the
 * {@code originRound} to reproduce the original block stream (subject to the unsigned-event intake
 * path being available on the replaying node — out of scope for this tool).
 *
 * <p>Event reconstruction is delegated to {@link BlockStreamEventBuilder} (steps 1–5: block items →
 * unsigned {@link PlatformEvent}s with recomputed hashes, in consensus/topological order). This
 * workflow performs step 6: writing those events to PCES files via {@link CommonPcesWriter},
 * following the {@code SavedStateUtils.prepareStateForTransplant()} idiom.
 *
 * <p><b>Limitations.</b> Redacted or filtered block streams are not supported (a redacted
 * transaction has no bytes to replay). The full set of blocks is loaded into memory at once
 * (see {@link BlockStreamEventBuilder}); very large extraction windows are constrained by available
 * memory. Chunked/streaming reconstruction is possible future work.
 */
public final class BlocksToPcesWorkflow {

    private static final Logger log = LogManager.getLogger(BlocksToPcesWorkflow.class);

    private BlocksToPcesWorkflow() {}

    /**
     * Reads block files from {@code blockStreamDirectory}, reconstructs events, and writes them to
     * PCES files under {@code pcesOutputDir}.
     *
     * @param blockStreamDirectory directory containing the {@code .blk.gz} files (must be contiguous)
     * @param pcesOutputDir directory where the PCES database tree is created (must be empty/new)
     * @param originRound the starting round of the state snapshot the PCES files will be replayed
     *     against; stamped as the PCES stream origin
     * @return the number of events written
     * @throws IOException if reading blocks or writing PCES files fails
     */
    public static long convert(NodeId selfId,
            @NonNull final Path blockStreamDirectory, @NonNull final Path pcesOutputDir, final long originRound)
            throws IOException {
        requireNonNull(blockStreamDirectory);
        requireNonNull(pcesOutputDir);

        validateNoMissingBlocks(blockStreamDirectory);

        // Step 1–5: reconstruct events from the blocks.
        final List<Block> blocks;
        try (var stream = BlockStreamAccess.readBlocks(blockStreamDirectory, false)) {
            blocks = stream.toList();
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("No block files found in " + blockStreamDirectory);
        }
        log.info("Read {} block(s) from {}", blocks.size(), blockStreamDirectory);

        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder(blocks);
        final List<PlatformEvent> events = eventBuilder.getEvents();
        log.info("Reconstructed {} event(s) from the block stream", events.size());

        // Step 6: write the events to PCES files.
        writePcesFiles(selfId, events, pcesOutputDir, originRound);

        log.info("Wrote {} event(s) to PCES files under {}", events.size(), pcesOutputDir);
        return events.size();
    }

    /**
     * Writes the given events to PCES files under {@code pcesRootDir}, using the same
     * {@link CommonPcesWriter} mechanism the platform and {@code SavedStateUtils} use.
     */
    private static void writePcesFiles(NodeId selfId,
            @NonNull final List<PlatformEvent> events, @NonNull final Path pcesRootDir, final long originRound)
            throws IOException {

        Files.createDirectories(pcesRootDir);

        final PlatformContext platformContext = getPlatformContext();
        final Configuration configuration = platformContext.getConfiguration();

        // The output directory is the root of a fresh PCES database tree; the standard
        // <root>/<nodeId> sub-path is created beneath it. The tree starts empty.
        final Path databaseDirectory = pcesRootDir.resolve(Long.toString(selfId.id()));
        Files.createDirectories(databaseDirectory);

        final PcesFileManager fileManager = new PcesFileManager(
                configuration,
                platformContext.getMetrics(),
                platformContext.getTime(),
                new PcesFileTracker(),
                databaseDirectory,
                originRound);

        final CommonPcesWriter writer = new CommonPcesWriter(configuration, fileManager);
        // Without this, the writer assumes every event is already durable and writes nothing.
        writer.beginStreamingNewEvents();

        for (final PlatformEvent event : events) {
            writer.prepareOutputStream(event);
            writer.getCurrentMutableFile().writeEvent(event);
        }

        writer.syncCurrentFile();
        writer.closeCurrentMutableFile();
    }

    /**
     * Validates that the block files form a contiguous sequence with no gaps, providing an early,
     * descriptive failure rather than a confusing parent-resolution error during reconstruction.
     * Mirrors the check in {@code BlockStreamRecoveryWorkflow}.
     */
    private static void validateNoMissingBlocks(@NonNull final Path blockStreamDirectory) throws IOException {
        final List<Long> blockNumbers;
        try (var stream = Files.walk(blockStreamDirectory)) {
            blockNumbers = stream.filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .map(BlockStreamAccess::extractBlockNumber)
                    .filter(n -> n != -1)
                    .sorted()
                    .toList();
        }

        if (blockNumbers.size() < 2) {
            return;
        }

        final List<Long> missingBlocks = new ArrayList<>();
        for (int i = 1; i < blockNumbers.size(); i++) {
            final long prev = blockNumbers.get(i - 1);
            final long curr = blockNumbers.get(i);
            for (long missing = prev + 1; missing < curr; missing++) {
                missingBlocks.add(missing);
            }
        }

        if (!missingBlocks.isEmpty()) {
            throw new RuntimeException(("Block stream directory is missing %d block file(s). "
                            + "First present block = %d, last present block = %d. Missing blocks: %s")
                    .formatted(
                            missingBlocks.size(),
                            blockNumbers.getFirst(),
                            blockNumbers.getLast(),
                            missingBlocks.size() <= 20
                                    ? missingBlocks.toString()
                                    : missingBlocks.subList(0, 20) + " ... (" + missingBlocks.size() + " total)"));
        }

        log.info(
                "Block file contiguity validated: {} block files present, range [{}, {}]",
                blockNumbers.size(),
                blockNumbers.getFirst(),
                blockNumbers.getLast());
    }
}
