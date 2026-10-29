// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.hapi.utils.blocks.StateChangeReconstructor;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Expands partial state changes in the blocks of one stream, delivered in increasing order, for listeners that
 * translate state values. Stays idle until the first block with a partial update, then replays the completed
 * blocks before it from the stream's source; later gaps are filled the same way. An expansion failure is terminal
 * and must be surfaced to consumers; sparse values must never be presented as complete state.
 */
public final class FullStateValues {
    private static final Logger log = LogManager.getLogger(FullStateValues.class);

    /** Loads the completed blocks of a stream. */
    @FunctionalInterface
    interface EarlierBlocks {
        /**
         * Returns the completed blocks numbered from {@code from} (or the first available, if negative) up to,
         * but excluding, {@code to}, in order; throws if any are missing.
         */
        List<Block> load(long from, long to);
    }

    @Nullable
    private StateChangeReconstructor reconstructor;

    private long nextBlock;

    @Nullable
    private IllegalStateException failure;

    /**
     * Returns the block with every partial update replaced by its full value, or the block itself when nothing
     * needs expanding. Throws if a complete baseline cannot be recovered. The result must not be used to verify proofs.
     */
    public synchronized Block expand(@NonNull final Path blockDir, @NonNull final Block block) {
        requireNonNull(blockDir);
        return expand(
                blockDir.toString(),
                (from, to) -> {
                    final var earlier = new java.util.ArrayList<Block>();
                    if (from < 0) {
                        final var archive = previewArchiveFor(blockDir);
                        if (Files.isDirectory(archive)) {
                            earlier.addAll(completedBlocks(archive, -1, -1));
                        }
                    }
                    earlier.addAll(completedBlocks(blockDir, from, to));
                    return earlier;
                },
                block);
    }

    /**
     * As {@link #expand(Path, Block)}, loading earlier blocks of the stream described by {@code source} from
     * {@code earlierBlocks}.
     */
    synchronized Block expand(
            @NonNull final String source, @NonNull final EarlierBlocks earlierBlocks, @NonNull final Block block) {
        requireNonNull(source);
        requireNonNull(earlierBlocks);
        requireNonNull(block);
        if (failure != null) throw failure;
        if (reconstructor == null && !StateChangeDeltas.hasPartialUpdates(block)) {
            return block;
        }
        try {
            final long number = numberOf(block);
            if (reconstructor == null) {
                log.info("Expanding partial state changes in {} from block #{}", source, number);
                reconstructor = new StateChangeReconstructor();
                nextBlock = -1;
            } else if (number < nextBlock) {
                throw new IllegalStateException("Out-of-order block #" + number + "; expected #" + nextBlock);
            }
            for (final var earlier : earlierBlocks.load(nextBlock, number)) {
                reconstructor.fullValuesForTranslation(earlier);
            }
            final var expanded = reconstructor.fullValuesForTranslation(block);
            nextBlock = number + 1;
            return expanded;
        } catch (final RuntimeException e) {
            failure = new IllegalStateException("Cannot expand partial state changes in " + source, e);
            throw failure;
        }
    }

    /**
     * Cutover archives the configured stream root, retaining its per-node subdirectories. Replay the archive
     * as a separate prefix: preview block numbers can exceed the first active number after cutover resets
     * numbering to the record stream. Sorting both layers together would discard or reorder real updates.
     */
    private static Path previewArchiveFor(final Path dir) {
        final var parent = dir.getParent();
        if (dir.getFileName().toString().startsWith("block-") && parent != null) {
            return parent.resolveSibling(parent.getFileName() + "-preview-archive")
                    .resolve(dir.getFileName());
        }
        return dir.resolveSibling(dir.getFileName() + "-preview-archive");
    }

    /** The completed blocks numbered from {@code from} (or the first on disk, if negative) up to {@code to}. */
    private static List<Block> completedBlocks(@NonNull final Path dir, final long from, final long to) {
        // One file per block number, preferring a finished block file over a pending one
        final var paths = new TreeMap<Long, Path>();
        try (final var files = Files.list(dir)) {
            files.filter(path -> BlockStreamAccess.isBlockFile(path, true)).forEach(path -> {
                final long number = BlockStreamAccess.extractBlockNumber(path);
                if (number >= from
                        && (to < 0 || number < to)
                        && !path.getFileName().toString().contains(".pnd")) {
                    paths.put(number, path);
                } else if (number >= from && (to < 0 || number < to)) {
                    paths.putIfAbsent(number, path);
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final long end = to >= 0 ? to : paths.isEmpty() ? 0 : paths.lastKey() + 1;
        final long first = from >= 0 ? from : paths.isEmpty() ? end : paths.firstKey();
        if (end - first != paths.size()) {
            throw new IllegalStateException("Blocks #" + first + " to #" + (end - 1) + " are incomplete in " + dir);
        }
        return paths.values().stream().map(BlockStreamAccess::blockFrom).toList();
    }

    private static long numberOf(@NonNull final Block block) {
        return block.items().stream()
                .filter(BlockItem::hasBlockHeader)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Block has no header"))
                .blockHeaderOrThrow()
                .number();
    }
}
