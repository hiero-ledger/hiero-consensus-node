// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.util.ThrottledLogging;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.DiskSpaceConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.PathsConfig;

/**
 * Pauses round handling while any data volume of this node has less free space than
 * {@link DiskSpaceConfig#reservedBytes()}.
 *
 * <p>The data volumes are the file stores of the saved state directory (which also holds the MerkleDB and PCES
 * files) and of the block, block buffer and record directories that the current stream mode writes. While the gate is
 * closed the handle thread waits, so the platform falls behind, stops creating events and leaves {@code ACTIVE} until
 * an operator frees space. The gate only delays handling; it never changes what a round does.
 */
@Singleton
public class DiskSpaceGate {
    private static final Logger logger = LogManager.getLogger(DiskSpaceGate.class);

    private static final Duration STILL_PAUSED_LOG_INTERVAL = Duration.ofMinutes(1);

    /**
     * Finds the file store of a directory.
     */
    @FunctionalInterface
    interface FileStoreLookup {
        @NonNull
        FileStore lookup(@NonNull Path dir) throws IOException;
    }

    /**
     * Waits between free space checks.
     */
    @FunctionalInterface
    interface Sleeper {
        void sleep(@NonNull Duration duration) throws InterruptedException;
    }

    private record Volume(@NonNull Path dir, @NonNull FileStore store) {}

    private record Shortfall(@NonNull Path dir, long usableBytes) {}

    private final ConfigProvider configProvider;
    private final Sleeper sleeper;
    private final List<Volume> volumes;
    private final ThrottledLogging readFailureLogging = new ThrottledLogging();

    @Inject
    public DiskSpaceGate(@NonNull final ConfigProvider configProvider, @NonNull final Platform platform) {
        this(
                configProvider,
                dataDirs(
                        configProvider.getConfiguration(), platform.getContext().getConfiguration()),
                DiskSpaceGate::fileStoreOf,
                Thread::sleep);
    }

    DiskSpaceGate(
            @NonNull final ConfigProvider configProvider,
            @NonNull final List<Path> dirs,
            @NonNull final FileStoreLookup fileStoreLookup,
            @NonNull final Sleeper sleeper) {
        this.configProvider = requireNonNull(configProvider);
        this.sleeper = requireNonNull(sleeper);
        this.volumes = volumesOf(requireNonNull(dirs), requireNonNull(fileStoreLookup));
    }

    /**
     * Returns once every data volume has at least the reserved free space, checking again every
     * {@link DiskSpaceConfig#recheckInterval()} until then. Returns at once if the reserve is zero or the thread is
     * interrupted.
     */
    public void awaitFreeSpace() {
        final var config = configProvider.getConfiguration().getConfigData(DiskSpaceConfig.class);
        final long reservedBytes = config.reservedBytes();
        if (reservedBytes <= 0) {
            return;
        }
        var shortfall = findShortfall(reservedBytes);
        if (shortfall == null) {
            return;
        }
        logger.error(
                "Pausing round handling: {} has {} bytes free, less than diskSpace.reservedBytes={}",
                shortfall.dir(),
                shortfall.usableBytes(),
                reservedBytes);
        final long startNanos = System.nanoTime();
        long lastLogNanos = startNanos;
        while (shortfall != null) {
            try {
                sleeper.sleep(config.recheckInterval());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            shortfall = findShortfall(reservedBytes);
            final long nowNanos = System.nanoTime();
            if (shortfall != null && nowNanos - lastLogNanos >= STILL_PAUSED_LOG_INTERVAL.toNanos()) {
                logger.error(
                        "Round handling still paused: {} has {} bytes free, less than diskSpace.reservedBytes={}",
                        shortfall.dir(),
                        shortfall.usableBytes(),
                        reservedBytes);
                lastLogNanos = nowNanos;
            }
        }
        logger.info(
                "Resuming round handling after {} ms; every data volume has at least {} bytes free",
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                reservedBytes);
    }

    @Nullable
    private Shortfall findShortfall(final long reservedBytes) {
        for (final var volume : volumes) {
            final long usableBytes;
            try {
                usableBytes = volume.store().getUsableSpace();
            } catch (final IOException e) {
                // A volume we cannot read must not stop the node
                if (readFailureLogging.shouldLog(e)) {
                    logger.warn("Could not read the free space of {}", volume.dir(), e);
                }
                continue;
            }
            if (usableBytes < reservedBytes) {
                return new Shortfall(volume.dir(), usableBytes);
            }
        }
        return null;
    }

    /**
     * Returns the directories this node writes data to, given its configuration.
     *
     * @param appConfig the services configuration
     * @param platformConfig the platform configuration
     * @return the data directories
     */
    static List<Path> dataDirs(@NonNull final Configuration appConfig, @NonNull final Configuration platformConfig) {
        final List<Path> dirs = new ArrayList<>();
        dirs.add(platformConfig.getConfigData(PathsConfig.class).savedStateDir());
        final var blockStreamConfig = appConfig.getConfigData(BlockStreamConfig.class);
        final var streamMode = blockStreamConfig.streamMode();
        if (streamMode != RECORDS) {
            dirs.add(Path.of(blockStreamConfig.blockFileDir()));
        }
        if (streamMode != BLOCKS) {
            dirs.add(Path.of(
                    appConfig.getConfigData(BlockRecordStreamConfig.class).logDir()));
        }
        final var bufferConfig = appConfig.getConfigData(BlockBufferConfig.class);
        if (blockStreamConfig.streamToBlockNodes() && bufferConfig.isBufferPersistenceEnabled()) {
            dirs.add(Path.of(bufferConfig.bufferDirectory()));
        }
        return dirs;
    }

    /**
     * Returns the file store of a directory, or of its nearest existing parent if the directory is not created yet.
     *
     * @param dir an absolute directory path
     * @return the file store the directory is, or will be, on
     * @throws IOException if no part of the path exists or the file store cannot be read
     */
    static FileStore fileStoreOf(@NonNull final Path dir) throws IOException {
        var existing = dir;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new NoSuchFileException(dir.toString());
        }
        return Files.getFileStore(existing);
    }

    private static List<Volume> volumesOf(
            @NonNull final List<Path> dirs, @NonNull final FileStoreLookup fileStoreLookup) {
        // Look up each file store once; the lookup can read the whole mount table
        final Map<FileStore, Path> dirsByStore = new LinkedHashMap<>();
        for (final var dir : dirs) {
            final var absoluteDir = dir.toAbsolutePath().normalize();
            try {
                dirsByStore.putIfAbsent(fileStoreLookup.lookup(absoluteDir), absoluteDir);
            } catch (final IOException e) {
                logger.warn("Free disk space of {} will not be checked", absoluteDir, e);
            }
        }
        final List<Volume> volumes = new ArrayList<>();
        dirsByStore.forEach((store, dir) -> volumes.add(new Volume(dir, store)));
        logger.info("Checking free disk space before each round in {}", dirsByStore.values());
        return List.copyOf(volumes);
    }
}
