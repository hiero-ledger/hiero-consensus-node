// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.TAG_TYPE_BITS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.WIRE_TYPE_DELIMITED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Appends {@link WrappedRecordFileBlockHashes} entries to a single on-disk file.
 */
@Singleton
public class WrappedRecordFileBlockHashesDiskWriter implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(WrappedRecordFileBlockHashesDiskWriter.class);

    public static final String DEFAULT_FILE_NAME = "wrapped-record-hashes.pb";

    /**
     * Field number for {@code WrappedRecordFileBlockHashesLog.entries}.
     */
    private static final int ENTRIES_FIELD_NUMBER = 1;

    private final ConfigProvider configProvider;
    private final FileSystem fileSystem;
    private final BlockStreamMetrics blockStreamMetrics;

    private final ExecutorService executor;
    private final AtomicReference<CompletableFuture<Void>> tail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final WrappedRecordHashesIndex index = new WrappedRecordHashesIndex();

    @Inject
    public WrappedRecordFileBlockHashesDiskWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.configProvider = requireNonNull(configProvider);
        this.fileSystem = requireNonNull(fileSystem);
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics);
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Enqueues an async task that computes and appends a single entry to the on-disk log file.
     * The task also updates min/max/gap metrics for the file after a successful append.
     *
     * <p>Tasks are executed in order on a single thread to preserve append order and avoid concurrent writes.
     *
     * @param input the immutable snapshot of record-block inputs
     * @return a future completed when the append task has finished
     */
    public CompletableFuture<Void> appendAsync(@NonNull final WrappedRecordFileBlockHashesComputationInput input) {
        requireNonNull(input);
        if (!configProvider
                .getConfiguration()
                .getConfigData(BlockRecordStreamConfig.class)
                .writeWrappedRecordFileBlockHashesToDisk()) {
            return CompletableFuture.completedFuture(null);
        }

        if (input.recordStreamItems().isEmpty()) {
            logger.warn(
                    "Skipping wrapped record-file block hashes append for block {} because recordStreamItems is empty; "
                            + "input{startRunningHashLen={}, endRunningHashLen={}, sidecars={}}",
                    input.blockNumber(),
                    input.startRunningHash().length(),
                    input.endRunningHash().length(),
                    input.sidecarRecords().size());
            return CompletableFuture.completedFuture(null);
        }

        ensureInitialized();

        return tail.updateAndGet(prev -> prev.thenRunAsync(
                        () -> {
                            final WrappedRecordFileBlockHashes entry;
                            try {
                                entry = WrappedRecordFileBlockHashesCalculator.compute(input);
                            } catch (final Exception e) {
                                logger.error(
                                        "Failed to compute wrapped record-file block hashes for block {}",
                                        input.blockNumber(),
                                        e);
                                return;
                            }

                            if (index.contains(entry.blockNumber())) {
                                logger.info(
                                        "Skipping wrapped record-file block hashes append for block {} because it is already present in {}",
                                        entry.blockNumber(),
                                        DEFAULT_FILE_NAME);
                                return;
                            }

                            if (logger.isDebugEnabled()) {
                                logger.debug(
                                        "Appending wrapped record-file block hashes for block {}: consensusTimestampLeafHash {}, outputItemsRootHash {}",
                                        entry.blockNumber(),
                                        entry.consensusTimestampHash().toHex(),
                                        entry.outputItemsTreeRootHash().toHex());
                            }

                            if (!appendInternal(entry)) {
                                return;
                            }

                            final var newlyLoggedGaps = index.addAndGetNewGaps(entry.blockNumber());
                            for (final var gap : newlyLoggedGaps) {
                                logger.warn(
                                        "Wrapped record hashes file has a gap: missing record blocks {}..{} (observed range {}..{})",
                                        gap.startInclusive(),
                                        gap.endInclusive(),
                                        index.lowestBlock(),
                                        index.highestBlock());
                            }

                            blockStreamMetrics.recordWrappedRecordHashesLowestBlock(index.lowestBlock());
                            blockStreamMetrics.recordWrappedRecordHashesHighestBlock(index.highestBlock());
                            blockStreamMetrics.recordWrappedRecordHashesHasGaps(index.hasGaps());
                        },
                        executor)
                .exceptionally(ex -> {
                    // Swallow to keep the chain alive; errors are logged in-task.
                    return null;
                }));
    }

    private void ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        final var cfg = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final Path dir = fileSystem.getPath(cfg.wrappedRecordHashesDir());
        final Path file = dir.resolve(DEFAULT_FILE_NAME);

        if (!Files.exists(file)) {
            // Seed metrics for an empty/non-existent file.
            blockStreamMetrics.recordWrappedRecordHashesLowestBlock(-1);
            blockStreamMetrics.recordWrappedRecordHashesHighestBlock(-1);
            blockStreamMetrics.recordWrappedRecordHashesHasGaps(false);
            return;
        }

        try {
            final var allBytes = Files.readAllBytes(file);
            if (allBytes.length == 0) {
                blockStreamMetrics.recordWrappedRecordHashesLowestBlock(-1);
                blockStreamMetrics.recordWrappedRecordHashesHighestBlock(-1);
                blockStreamMetrics.recordWrappedRecordHashesHasGaps(false);
                return;
            }

            // The file contents are an append-only sequence of occurrences of the `entries` field,
            // which is a valid protobuf encoding of the container message.
            final var log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    com.hedera.pbj.runtime.io.buffer.Bytes.wrap(allBytes).toReadableSequentialData(),
                    false,
                    false,
                    512,
                    allBytes.length);
            for (final var entry : log.entries()) {
                index.add(entry.blockNumber());
            }
        } catch (final Exception e) {
            logger.error("Failed to scan existing wrapped record hashes file {}. Recreating...", file, e);
            // If we cannot parse the existing file, treat it as corrupt and recreate it empty so subsequent appends
            // can proceed. This is best-effort and must not prevent node startup.
            try {
                index.reset();
                Files.createDirectories(dir);
                try (final var ignored = Files.newOutputStream(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    // Intentionally empty; truncates/creates file.
                }
            } catch (final Exception ex) {
                logger.warn("Failed to recreate corrupt wrapped record hashes file {} as empty", file, ex);
            }
        }

        // If there are already gaps in the file, log them once at startup and remember we logged them.
        if (index.highestBlock() >= 0) {
            final var initGaps = index.addAndGetNewGaps(index.highestBlock());
            for (final var gap : initGaps) {
                logger.warn(
                        "Wrapped record hashes file has a gap: missing record blocks {}..{} (observed range {}..{})",
                        gap.startInclusive(),
                        gap.endInclusive(),
                        index.lowestBlock(),
                        index.highestBlock());
            }
        }

        blockStreamMetrics.recordWrappedRecordHashesLowestBlock(index.lowestBlock());
        blockStreamMetrics.recordWrappedRecordHashesHighestBlock(index.highestBlock());
        blockStreamMetrics.recordWrappedRecordHashesHasGaps(index.hasGaps());
    }

    /**
     * Appends a single entry to the on-disk log file.
     *
     * @return true if the append succeeded
     */
    private boolean appendInternal(@NonNull final WrappedRecordFileBlockHashes entry) {
        requireNonNull(entry);
        final var cfg = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final Path dir = fileSystem.getPath(cfg.wrappedRecordHashesDir());
        final Path file = dir.resolve(DEFAULT_FILE_NAME);

        try {
            Files.createDirectories(dir);
            try (final var out = Files.newOutputStream(
                            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    final var buffered = new BufferedOutputStream(out);
                    final var stream = new WritableStreamingData(buffered)) {
                final var bytes = WrappedRecordFileBlockHashes.PROTOBUF.toBytes(entry);
                stream.writeVarInt((ENTRIES_FIELD_NUMBER << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
                stream.writeVarInt((int) bytes.length(), false);
                stream.writeBytes(bytes);
            }
            return true;
        } catch (final IOException | UncheckedIOException e) {
            logger.error(
                    "Failed to append wrapped record-file block hashes for block {} to {}",
                    entry.blockNumber(),
                    file,
                    e);
            return false;
        }
    }

    @Override
    public void close() {
        // Ensure all queued appends are flushed to disk before shutting down.
        try {
            tail.get().join();
        } catch (final Exception e) {
            // The chain should swallow exceptions, but be defensive.
            logger.warn("Error while awaiting completion of wrapped record hashes append chain", e);
        } finally {
            executor.shutdown();
        }
    }
}
