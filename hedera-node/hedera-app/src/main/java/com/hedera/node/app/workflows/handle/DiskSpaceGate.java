// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.config.types.DiskNetworkExport.NEVER;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.util.ThrottledLogging;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.DiskSpaceConfig;
import com.hedera.node.config.data.FailureBlockStagingConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.TssConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.MemoryMappedFileAppender;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.config.RecycleBinConfig;
import org.hiero.consensus.event.stream.config.EventConfig;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.pces.config.PcesConfig;

/**
 * Pauses round handling while any volume this node writes to has less free space than
 * {@link DiskSpaceConfig#reservedMb()}.
 *
 * <p>The volumes are the file stores of the directories the node is configured to write to (saved states with the
 * MerkleDB and PCES files, the event, block and record streams, the block buffer, upgrade files, TSS keys, staged ISS
 * blocks, saved pending transactions and metrics), plus the log and working directories. While the gate is closed the
 * handle thread waits, so the platform falls behind, stops creating events and leaves {@code ACTIVE} until an operator
 * frees space. The gate only delays handling; it never changes what a round does.
 *
 * <p>The reserve comes from the bootstrap config (local files, env and system properties), never from file 0.0.121:
 * a too-high network-wide value would pause every node, and a paused network could not handle the fix.
 */
@Singleton
public class DiskSpaceGate {
    private static final Logger logger = LogManager.getLogger(DiskSpaceGate.class);

    private static final Duration STILL_PAUSED_LOG_INTERVAL = Duration.ofMinutes(1);
    private static final long BYTES_PER_MB = 1024L * 1024L;

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

    private final DiskSpaceConfig config;
    private final Sleeper sleeper;
    private final List<Volume> volumes;
    private final ThrottledLogging readFailureLogging = new ThrottledLogging();
    private final LongGauge freeBytesGauge;
    private final LongGauge reservedBytesGauge;
    private final LongGauge pausedGauge;

    @Inject
    public DiskSpaceGate(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BootstrapConfigProviderImpl bootstrapConfigProvider,
            @NonNull final Platform platform,
            @NonNull final Metrics metrics) {
        this(
                bootstrapConfigProvider.configuration().getConfigData(DiskSpaceConfig.class),
                metrics,
                dataDirs(
                        configProvider.getConfiguration(), platform.getContext().getConfiguration(), logDirs()),
                DiskSpaceGate::fileStoreOf,
                Thread::sleep);
    }

    DiskSpaceGate(
            @NonNull final DiskSpaceConfig config,
            @NonNull final Metrics metrics,
            @NonNull final List<Path> dirs,
            @NonNull final FileStoreLookup fileStoreLookup,
            @NonNull final Sleeper sleeper) {
        this.config = requireNonNull(config);
        this.sleeper = requireNonNull(sleeper);
        this.volumes = volumesOf(requireNonNull(dirs), requireNonNull(fileStoreLookup));
        requireNonNull(metrics);
        freeBytesGauge = metrics.getOrCreate(new LongGauge.Config("app", "diskSpaceFree")
                .withDescription("Lowest free space on the volumes this node writes to, checked before each round")
                .withUnit("bytes"));
        reservedBytesGauge = metrics.getOrCreate(new LongGauge.Config("app", "diskSpaceReserved")
                .withDescription("Free space every volume must keep before a round starts (diskSpace.reservedMb)")
                .withUnit("bytes"));
        pausedGauge = metrics.getOrCreate(new LongGauge.Config("app", "diskSpacePaused")
                .withDescription("1 while round handling waits for free disk space, else 0"));
        warnAboutVolumesNotBiggerThanTheReserve(config);
    }

    /**
     * Returns once every volume has at least the reserved free space, checking again every
     * {@link DiskSpaceConfig#recheckInterval()} until then. Returns at once if the reserve is zero or the thread is
     * interrupted.
     */
    public void awaitFreeSpace() {
        final long reservedBytes = config.reservedMb() * BYTES_PER_MB;
        reservedBytesGauge.set(reservedBytes);
        if (reservedBytes <= 0) {
            return;
        }
        final var shortfall = findShortfall(reservedBytes);
        if (shortfall == null) {
            return;
        }
        pausedGauge.set(1);
        try {
            waitWhilePaused(config, reservedBytes, shortfall);
        } finally {
            pausedGauge.set(0);
        }
    }

    private void waitWhilePaused(
            @NonNull final DiskSpaceConfig config, final long reservedBytes, @NonNull final Shortfall firstShortfall) {
        logger.error(
                "Pausing round handling: {} has {} MiB free, less than diskSpace.reservedMb={}",
                firstShortfall.dir(),
                firstShortfall.usableBytes() / BYTES_PER_MB,
                config.reservedMb());
        final long startNanos = System.nanoTime();
        long lastLogNanos = startNanos;
        while (true) {
            try {
                sleeper.sleep(config.recheckInterval());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            final var shortfall = findShortfall(reservedBytes);
            if (shortfall == null) {
                break;
            }
            final long nowNanos = System.nanoTime();
            if (nowNanos - lastLogNanos >= STILL_PAUSED_LOG_INTERVAL.toNanos()) {
                logger.error(
                        "Round handling still paused: {} has {} MiB free, less than diskSpace.reservedMb={}",
                        shortfall.dir(),
                        shortfall.usableBytes() / BYTES_PER_MB,
                        config.reservedMb());
                lastLogNanos = nowNanos;
            }
        }
        logger.info(
                "Resuming round handling after {} ms; every volume has at least {} MiB free",
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                config.reservedMb());
    }

    @Nullable
    private Shortfall findShortfall(final long reservedBytes) {
        Shortfall shortfall = null;
        long lowestUsableBytes = Long.MAX_VALUE;
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
            lowestUsableBytes = Math.min(lowestUsableBytes, usableBytes);
            if (shortfall == null && usableBytes < reservedBytes) {
                shortfall = new Shortfall(volume.dir(), usableBytes);
            }
        }
        if (lowestUsableBytes != Long.MAX_VALUE) {
            freeBytesGauge.set(lowestUsableBytes);
        }
        return shortfall;
    }

    private void warnAboutVolumesNotBiggerThanTheReserve(@NonNull final DiskSpaceConfig config) {
        final long reservedBytes = config.reservedMb() * BYTES_PER_MB;
        if (reservedBytes <= 0) {
            return;
        }
        for (final var volume : volumes) {
            try {
                final long totalBytes = volume.store().getTotalSpace();
                if (totalBytes <= reservedBytes) {
                    logger.warn(
                            "{} is {} MiB in total, not more than diskSpace.reservedMb={}; rounds will stay paused",
                            volume.dir(),
                            totalBytes / BYTES_PER_MB,
                            config.reservedMb());
                }
            } catch (final IOException e) {
                // awaitFreeSpace() reports volumes it cannot read
            }
        }
    }

    /**
     * Returns the directories this node is configured to write to, plus the log and working directories.
     *
     * @param appConfig the services configuration
     * @param platformConfig the platform configuration
     * @param logDirs the directories of the log files
     * @return the directories; relative ones are relative to the working directory
     */
    static List<Path> dataDirs(
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @NonNull final Collection<Path> logDirs) {
        final List<Path> dirs = new ArrayList<>();
        final var pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final var savedStateDir = pathsConfig.savedStateDir();
        dirs.add(savedStateDir);
        dirs.add(savedStateDir.resolve(pathsConfig.tmpDir()));
        ifRegistered(platformConfig, PcesConfig.class, c -> dirs.add(savedStateDir.resolve(c.databaseDirectory())));
        ifRegistered(platformConfig, RecycleBinConfig.class, c -> dirs.add(savedStateDir.resolve(c.dirName())));
        ifRegistered(platformConfig, EventConfig.class, c -> {
            if (c.enableEventStreaming()) {
                dirs.add(Path.of(c.eventsLogDir()));
            }
        });
        ifRegistered(platformConfig, MetricsConfig.class, c -> {
            if (!c.disableMetricsOutput() && !c.csvFileName().isBlank()) {
                dirs.add(Path.of(c.csvOutputFolder()));
            }
        });

        final var blockStreamConfig = appConfig.getConfigData(BlockStreamConfig.class);
        final var streamMode = blockStreamConfig.streamMode();
        if (streamMode != RECORDS) {
            dirs.add(Path.of(blockStreamConfig.blockFileDir()));
        }
        final var recordStreamConfig = appConfig.getConfigData(BlockRecordStreamConfig.class);
        if (streamMode != BLOCKS) {
            dirs.add(Path.of(recordStreamConfig.logDir()));
        }
        if (recordStreamConfig.writeWrappedRecordFileBlockHashesToDisk()
                && !recordStreamConfig.wrappedRecordHashesDir().isBlank()) {
            dirs.add(Path.of(recordStreamConfig.wrappedRecordHashesDir()));
        }
        final var bufferConfig = appConfig.getConfigData(BlockBufferConfig.class);
        if (blockStreamConfig.streamToBlockNodes() && bufferConfig.isBufferPersistenceEnabled()) {
            dirs.add(Path.of(bufferConfig.bufferDirectory()));
        }
        final var networkAdminConfig = appConfig.getConfigData(NetworkAdminConfig.class);
        dirs.add(Path.of(networkAdminConfig.upgradeArtifactsPath()));
        if (networkAdminConfig.diskNetworkExport() != NEVER) {
            dirs.add(Path.of(networkAdminConfig.diskNetworkExportFile())
                    .toAbsolutePath()
                    .getParent());
        }
        final var tssConfig = appConfig.getConfigData(TssConfig.class);
        if (tssConfig.hintsEnabled() || tssConfig.historyEnabled()) {
            dirs.add(Path.of(tssConfig.tssKeysPath()));
        }
        final var stagingConfig = appConfig.getConfigData(FailureBlockStagingConfig.class);
        if (stagingConfig.issBlockStagingEnabled() || stagingConfig.triageStagingEnabled()) {
            dirs.add(Path.of(stagingConfig.issBlockDir()));
        }
        final var hederaConfig = appConfig.getConfigData(HederaConfig.class);
        if (hederaConfig.pendingTransactionsPersistenceEnabled()) {
            dirs.add(Path.of(hederaConfig.pendingTransactionsDirectory()));
        }
        dirs.addAll(logDirs);
        // Heap dumps, crash files and anything else written to a relative path
        dirs.add(Path.of(""));
        return dirs;
    }

    /**
     * Returns the directories of the log files that log4j writes.
     *
     * @return the log directories
     */
    static List<Path> logDirs() {
        return LogManager.getContext(false) instanceof LoggerContext context
                ? logDirsOf(context.getConfiguration().getAppenders().values())
                : List.of();
    }

    /**
     * Returns the directories of the files the given appenders write.
     *
     * @param appenders the log4j appenders
     * @return the directories of their files
     */
    static List<Path> logDirsOf(@NonNull final Collection<Appender> appenders) {
        final List<Path> dirs = new ArrayList<>();
        for (final var appender : appenders) {
            final String fileName = switch (appender) {
                case FileAppender file -> file.getFileName();
                case RollingFileAppender file -> file.getFileName();
                case RandomAccessFileAppender file -> file.getFileName();
                case RollingRandomAccessFileAppender file -> file.getFileName();
                case MemoryMappedFileAppender file -> file.getFileName();
                default -> null;
            };
            if (fileName != null) {
                dirs.add(Path.of(fileName).toAbsolutePath().getParent());
            }
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

    private static <T extends Record> void ifRegistered(
            @NonNull final Configuration config, @NonNull final Class<T> type, @NonNull final Consumer<T> action) {
        // The embedded test platform registers only some platform config types
        if (config.getConfigDataTypes().contains(type)) {
            action.accept(config.getConfigData(type));
        }
    }

    private static List<Volume> volumesOf(
            @NonNull final List<Path> dirs, @NonNull final FileStoreLookup fileStoreLookup) {
        // Look up the file stores once; the lookup can read the whole mount table
        final Map<FileStore, Set<Path>> dirsByStore = new LinkedHashMap<>();
        for (final var dir : dirs) {
            final var absoluteDir = dir.toAbsolutePath().normalize();
            try {
                dirsByStore
                        .computeIfAbsent(fileStoreLookup.lookup(absoluteDir), store -> new LinkedHashSet<>())
                        .add(absoluteDir);
            } catch (final IOException e) {
                logger.warn("Free disk space of {} will not be checked", absoluteDir, e);
            }
        }
        final List<Volume> volumes = new ArrayList<>();
        dirsByStore.forEach((store, storeDirs) -> {
            volumes.add(new Volume(storeDirs.iterator().next(), store));
            logger.info("Checking free disk space before each round on {} for {}", store, storeDirs);
        });
        return List.copyOf(volumes);
    }
}
