// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BlockBytes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockState;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Locates the block containing a given ISS round in the in-memory {@link BlockBufferService} and writes it to disk as
 * an {@code .iss.gz} artifact, so it can be uploaded for triage. This is the source for the detection
 * path in {@code GRPC} mode, where closed blocks are never written to disk and the buffer is the only place the ISS
 * round still lives at detection time (the buffer retention floor is sized to keep it through the detection lag).
 *
 * <p>The round is found the same way as on disk: every round's first block item is a {@code RoundHeader}, so a block's
 * first {@code RoundHeader} is its first round, and first-round-per-block increases monotonically with block number.
 * The buffer is small (bounded by {@code maxBlocks}), so a linear scan over the contiguous buffered block numbers is
 * sufficient. The reconstruction mirrors {@code GrpcBlockItemWriter.flushIncompleteBlock()}: the buffered serialized
 * items are concatenated into a {@link BlockBytes} (wire-identical to a {@code Block}) and gzipped, so the file parses
 * back as a {@code Block} for analysis. Reads are a best-effort snapshot — a {@code null} item or a block pruned
 * mid-scan is tolerated and treated as a miss; nothing here ever locks the live stream.
 */
public class IssBufferBlockReader {
    private static final Logger log = LogManager.getLogger(IssBufferBlockReader.class);

    private static final String INCOMPLETE_EXT = ".iss.gz";

    private final BlockBufferService blockBufferService;

    @Inject
    public IssBufferBlockReader(@NonNull final BlockBufferService blockBufferService) {
        this.blockBufferService = requireNonNull(blockBufferService);
    }

    /**
     * Finds the block containing {@code round} in the buffer (plus up to {@code precedingBlocks} blocks before it) and
     * writes each as an {@code .iss.gz} file under {@code targetDir}.
     *
     * @param round the ISS round to locate
     * @param precedingBlocks how many preceding blocks to also capture (clamped to the earliest buffered block)
     * @param targetDir the directory to write the {@code .iss.gz} artifact(s) into
     * @return the written files ordered oldest→newest; empty if the round is not in the buffer or nothing was written
     */
    @NonNull
    public List<Path> captureToDir(final long round, final int precedingBlocks, @NonNull final Path targetDir) {
        requireNonNull(targetDir);
        final long earliest = blockBufferService.getEarliestAvailableBlockNumber();
        final long last = blockBufferService.getLastBlockNumberProduced();
        if (earliest < 0 || last < earliest) {
            log.warn("Block buffer is empty; cannot locate block for ISS round {}", round);
            return List.of();
        }

        // The containing block is the one with the largest first-round that is <= the ISS round.
        long issBlockNumber = -1;
        for (long n = earliest; n <= last; n++) {
            final BlockState state = blockBufferService.getBlockState(n);
            if (state == null) {
                continue; // pruned mid-scan or a gap; tolerate it
            }
            final OptionalLong firstRound = firstRoundOf(state);
            if (firstRound.isPresent() && firstRound.getAsLong() <= round) {
                issBlockNumber = n;
            }
        }
        if (issBlockNumber < 0) {
            log.warn(
                    "ISS round {} is not in the block buffer (earliest buffered #{}); nothing to upload",
                    round,
                    earliest);
            return List.of();
        }

        final long firstBlockNumber = Math.max(earliest, issBlockNumber - Math.max(0, precedingBlocks));
        final List<Path> written = new ArrayList<>();
        for (long n = firstBlockNumber; n <= issBlockNumber; n++) {
            final BlockState state = blockBufferService.getBlockState(n);
            if (state == null) {
                continue;
            }
            try {
                final Path path = reconstruct(n, state, targetDir);
                if (path != null) {
                    written.add(path);
                }
            } catch (final IOException e) {
                log.warn("Failed to write buffered ISS block #{} to {}", n, targetDir, e);
            }
        }
        final int found = written.isEmpty() ? 0 : written.size() - 1;
        if (found < Math.max(0, precedingBlocks)) {
            log.info(
                    "Requested {} preceding context block(s) for ISS round {} but only {} are buffered",
                    precedingBlocks,
                    round,
                    found);
        }
        log.info(
                "Captured ISS round {} from buffer block #{}; wrote {} block(s)",
                round,
                issBlockNumber,
                written.size());
        return written;
    }

    /** The first {@code RoundHeader}'s round number in a buffered block, found by item type without parsing every item. */
    private static OptionalLong firstRoundOf(@NonNull final BlockState state) {
        final int count = state.itemCount();
        for (int i = 0; i < count; i++) {
            final BlockState.BufferedItem item = state.bufferedItem(i);
            if (item != null && item.itemType() == BlockItem.ItemOneOfType.ROUND_HEADER) {
                try {
                    return OptionalLong.of(BlockItem.PROTOBUF
                            .parse(item.serializedItem())
                            .roundHeaderOrThrow()
                            .roundNumber());
                } catch (final ParseException e) {
                    return OptionalLong.empty();
                }
            }
        }
        return OptionalLong.empty();
    }

    /** Writes a buffered block's items as a gzipped {@link BlockBytes} {@code .iss.gz} file; null if it had no items. */
    private static Path reconstruct(
            final long blockNumber, @NonNull final BlockState state, @NonNull final Path targetDir) throws IOException {
        final int count = state.itemCount();
        final List<Bytes> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final BlockState.BufferedItem item = state.bufferedItem(i);
            if (item != null) {
                items.add(item.serializedItem());
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        Files.createDirectories(targetDir);
        final Path out = targetDir.resolve(FileBlockItemWriter.longToFileName(blockNumber) + INCOMPLETE_EXT);
        try (final GZIPOutputStream os = new GZIPOutputStream(Files.newOutputStream(out))) {
            os.write(BlockBytes.PROTOBUF.toBytes(new BlockBytes(items)).toByteArray());
        }
        return out;
    }
}
