// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.SIDECAR_ONLY_TOKEN;
import static java.util.Comparator.comparing;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central utility for accessing blocks.
 */
public enum BlockStreamAccess {
    BLOCK_STREAM_ACCESS;

    private static final Logger log = LogManager.getLogger(BlockStreamAccess.class);

    /**
     * Reads all files matching the block file pattern from the given path and returns them in
     * ascending order of block number.
     *
     * @param path the path to read blocks from
     * @return the stream of blocks
     * @throws UncheckedIOException if an I/O error occurs
     */
    public List<Block> readBlocks(@NonNull final Path path) {
        return readBlocks(path, true).toList();
    }
    /**
     * Reads all files matching the block file pattern from the given path and returns them in
     * ascending order of block number.
     *
     * @param path the path to read blocks from
     * @return the stream of blocks
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static Stream<Block> readBlocks(@NonNull final Path path, boolean checkForMarkerFiles) {
        try {
            return orderedBlocksFrom(path, checkForMarkerFiles).stream().map(BlockStreamAccess::blockFrom);
        } catch (IOException e) {
            log.error("Failed to read blocks from path {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads all files matching the marker file pattern from the given path
     * and returns the latest marker file with the highest block number.
     *
     * @param path the path to read blocks from
     * @return the ascending set of block marker file numbers
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static Set<Long> getAllMarkerFileNumbers(@NonNull final Path path) {
        try (final var stream = Files.walk(path)) {
            return stream.map(BlockStreamAccess::extractMarkerFileNumber)
                    .filter(num -> num != -1)
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            log.error("Failed to read blocks from path {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Given a list of blocks, computes the last singleton value for a certain state by applying the given
     * function to the {@link SingletonUpdateChange} block items.
     *
     * @param <V> the value type
     * @param blocks the list of blocks
     * @param extractFn the function to apply to a {@link SingletonUpdateChange} to get the value
     * @param stateId the ID of the state
     * @return the last singleton value
     */
    @Nullable
    public static <V> V computeSingletonValueFromUpdates(
            @NonNull final List<Block> blocks,
            @NonNull final Function<SingletonUpdateChange, V> extractFn,
            final int stateId) {
        final AtomicReference<V> lastValue = new AtomicReference<>();
        stateChangesForState(blocks, stateId)
                .filter(StateChange::hasSingletonUpdate)
                .map(StateChange::singletonUpdateOrThrow)
                .forEach(update -> lastValue.set(extractFn.apply(update)));
        return lastValue.get();
    }

    /**
     * Given a list of blocks, computes a map of key-value pairs that reflects the state changes for a certain
     * key type and value type by applying the given functions to the {@link StateChanges} block items.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param blocks the list of blocks
     * @param deleteFn the function to apply to a {@link MapDeleteChange} to get the key to remove
     * @param updateFn the function to apply to a {@link MapUpdateChange} to get the key-value pair to update
     * @param stateId the ID of the state
     * @return the map of key-value pairs
     */
    public static <K, V> Map<K, V> computeMapFromUpdates(
            @NonNull final List<Block> blocks,
            @NonNull final Function<MapChangeKey, K> deleteFn,
            @NonNull final Function<MapUpdateChange, Map.Entry<K, V>> updateFn,
            final int stateId) {
        final Map<K, V> upToDate = new HashMap<>();
        blocks.forEach(block -> block.items().stream()
                .filter(BlockItem::hasStateChanges)
                .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                .filter(change -> change.stateId() == stateId)
                .forEach(change -> {
                    if (change.hasMapDelete()) {
                        final var removedKey =
                                deleteFn.apply(change.mapDeleteOrThrow().keyOrThrow());
                        upToDate.remove(removedKey);
                    } else if (change.hasMapUpdate()) {
                        final var mapUpdate = change.mapUpdateOrThrow();
                        final var entry = updateFn.apply(mapUpdate);
                        upToDate.put(entry.getKey(), entry.getValue());
                    }
                }));
        return upToDate;
    }

    /**
     * Reads a single block from the given path.
     * @param path the path to read the block from
     * @return the block
     */
    public static Block blockFrom(@NonNull final Path path) {
        final var fileName = path.getFileName().toString();
        try {
            if (fileName.endsWith(".gz")) {
                try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(path))) {
                    return Block.PROTOBUF.parse(Bytes.wrap(in.readAllBytes()));
                }
            } else {
                return Block.PROTOBUF.parse(Bytes.wrap(Files.readAllBytes(path)));
            }
        } catch (IOException | ParseException e) {
            throw new RuntimeException("Failed reading block @ " + path, e);
        }
    }

    private static Stream<StateChange> stateChangesForState(@NonNull final List<Block> blocks, final int stateId) {
        return blocks.stream().flatMap(block -> block.items().stream()
                .filter(BlockItem::hasStateChanges)
                .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                .filter(change -> change.stateId() == stateId));
    }

    private static List<Path> orderedBlocksFrom(@NonNull final Path path, boolean checkForMarkerFiles)
            throws IOException {
        try (final var stream = Files.walk(path)) {
            return stream.filter(p -> isBlockFile(p, checkForMarkerFiles))
                    .sorted(comparing(BlockStreamAccess::extractBlockNumber))
                    .toList();
        }
    }

    /**
     * Checks if the given path is a block file.
     * @param path the path to check
     * @return true if the path is a block file, false otherwise
     */
    public static boolean isBlockFile(@NonNull final Path path, boolean checkForMarkerFiles) {
        if (!path.toFile().isFile() || extractBlockNumber(path) == -1) {
            return false;
        }
        final var name = path.getFileName().toString();
        if (name.endsWith(".pnd.json")) {
            return false;
        }
        if (name.endsWith(".pnd")) {
            return Files.exists(path.resolveSibling(name + ".json"));
        } else if (name.endsWith(".pnd.gz")) {
            return Files.exists(path.resolveSibling(name.replace(".gz", ".json")));
        }

        // Check for marker file
        final var markerFile =
                path.resolveSibling(name.replace(".blk.gz", ".mf").replace(".blk", ".mf"));
        return Files.exists(markerFile) || !checkForMarkerFiles;
    }

    /**
     * Extracts the block number from the given path.
     *
     * @param path the path
     * @return the block number
     */
    public static long extractBlockNumber(@NonNull final Path path) {
        return extractBlockNumber(path.getFileName().toString());
    }

    /**
     * Checks if the given file is a block file.
     *
     * @param file the file
     * @return true if the file is a block file, false otherwise
     */
    public static boolean isBlockFile(@NonNull final File file) {
        return file.isFile() && extractBlockNumber(file.getName()) != -1;
    }

    /**
     * Extracts the block number from the given file name.
     *
     * @param fileName the file name
     * @return the block number, or -1 if it cannot be extracted
     */
    public static long extractBlockNumber(@NonNull final String fileName) {
        try {
            int i = fileName.indexOf(".blk");
            if (i == -1) {
                i = fileName.indexOf(".pnd");
            }
            return Long.parseLong(fileName.substring(0, i));
        } catch (Exception ignore) {
        }
        return -1;
    }

    /**
     * Extracts the number from the given marker file.
     *
     * @param path the file name
     * @return the block number, or -1 if it cannot be extracted
     */
    private static long extractMarkerFileNumber(@NonNull final Path path) {
        final var fileName = path.getFileName().toString();

        if (!fileName.endsWith(".mf")) {
            return -1;
        }

        try {
            int i = fileName.indexOf(".mf");
            return Long.parseLong(fileName.substring(0, i));
        } catch (Exception ignore) {
        }
        return -1;
    }

    /**
     * Checks if the given file is a block marker file.
     *
     * @param file the file
     * @return true if the file is a block marker file, false otherwise
     */
    public static boolean isBlockMarkerFile(@NonNull final File file) {
        return file.isFile()
                && file.getName().endsWith(".mf")
                && !file.getName().contains(SIDECAR_ONLY_TOKEN);
    }
}
