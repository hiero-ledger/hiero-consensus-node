// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.orderedRecordFilesFrom;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.orderedSidecarFilesFrom;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.parseRecordFileConsensusTime;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.parseSidecarFileConsensusTimeAndSequenceNo;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.readMaybeCompressedRecordStreamFile;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.TEST_CLIENTS_PREFIX;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentWithCurrentPathPrefix;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.services.stream.proto.RecordStreamFile;
import com.hedera.services.stream.proto.SidecarFile;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A singleton that provides near real-time access to the record stream files for all concurrently
 * executing {@link com.hedera.services.bdd.spec.HapiSpec}'s.
 */
public enum StreamFileAccess {
    STREAM_FILE_ACCESS;

    private static final Logger log = LogManager.getLogger(StreamFileAccess.class);

    private static final int MONITOR_INTERVAL_MS = 250;

    /**
     * A map of stream file locations to the listeners that are watching them.
     * <p>
     * Note that in general we only validate stream files from {@code node0}, since if other nodes are producing
     * different files they are certain to hit an ISS in very short order.
     */
    private final Map<String, StreamFileAlterationListener> locationListeners = new ConcurrentHashMap<>();

    /** A bit of infrastructure that runs the polling loop for all the listeners. */
    private final FileAlterationMonitor monitor = new FileAlterationMonitor(MONITOR_INTERVAL_MS);

    public record RecordStreamData(List<RecordWithSidecars> records, List<RecordStreamFile> files) {}

    /**
     * Registers a listener for the record stream file at the given path. Returns a runnable that can
     * be used to unregister the listener.
     *
     * @param path the path to the record stream file
     * @param listener the listener to register
     * @return a runnable that can be used to unregister the listener
     */
    public synchronized Runnable subscribe(@NonNull final Path path, @NonNull final StreamDataListener listener) {
        requireNonNull(path);
        requireNonNull(listener);
        try {
            final List<File> maybeExistingFiles = new ArrayList<>();
            if (listener.replayExistingFiles()) {
                try (final var stream = Files.walk(path)) {
                    maybeExistingFiles.addAll(
                            stream.map(Path::toFile).filter(File::isFile).toList());
                }
            }
            if (listener.replayExistingFiles()) {
                final var mockAlterationListener = new StreamFileAlterationListener();
                mockAlterationListener.subscribe(listener);
                CompletableFuture.runAsync(() -> maybeExistingFiles.forEach(mockAlterationListener::onFileCreate));
            }
            final var alterationListener =
                    getOrCreateListener(path.toAbsolutePath().normalize().toString());
            final var unsubscribe = alterationListener.subscribe(listener);
            // WARNING - replay is only meant for something like @GenesisHapiTest where there is
            // at most one subscriber and a small number of files to replay; use with caution
            return () -> {
                try {
                    unsubscribe.run();
                    stopMonitorIfNoSubscribers();
                } catch (final Exception e) {
                    log.error("Failed to unregister listener for " + path, e);
                }
            };
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to register listener for " + path, e);
        }
    }

    /**
     * Stops the polling loop for record stream access if there are no listeners for any location.
     */
    public synchronized void stopMonitorIfNoSubscribers() {
        // Count the number of subscribers (could derive from more than one concurrent HapiSpec)
        final var numSubscribers = locationListeners.values().stream()
                .mapToInt(StreamFileAlterationListener::numListeners)
                .sum();
        if (numSubscribers == 0) {
            try {
                if (!locationListeners.isEmpty()) {
                    log.info("Stopping record stream access monitor (locations were {})", locationListeners.keySet());
                    locationListeners.clear();
                }
                // Remove all observers and stop the monitor
                monitor.getObservers().forEach(monitor::removeObserver);
                // Will throw ISE if already stopped, ignore that
                monitor.stop();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * Reads the record and sidecar stream files from a given directory.
     *
     * @param loc the directory to read from
     * @param relativeSidecarLoc the relative location of the sidecar files
     * @return the list of record and sidecar files
     * @throws IOException if there is an error reading the files
     */
    public RecordStreamData readStreamDataFrom(String loc, final String relativeSidecarLoc) throws IOException {
        return readStreamDataFrom(loc, relativeSidecarLoc, f -> true);
    }

    /**
     * Reads the record and sidecar stream files from a given directory, skipping any record files that do
     * not pass the given inclusion test.
     *
     * @param loc the directory to read from
     * @param relativeSidecarLoc the relative location of the sidecar files
     * @param inclusionTest a predicate to filter the record files
     * @return the list of record and sidecar files
     * @throws IOException if there is an error reading the files
     */
    public RecordStreamData readStreamDataFrom(
            @NonNull String loc,
            @NonNull final String relativeSidecarLoc,
            @NonNull final Predicate<String> inclusionTest)
            throws IOException {
        final var fAtLoc = relocatedIfNotPresentWithCurrentPathPrefix(new File(loc), "..", TEST_CLIENTS_PREFIX);
        loc = fAtLoc.getAbsolutePath();
        final var recordFiles = orderedRecordFilesFrom(loc, inclusionTest);
        final var sidecarLoc = loc + File.separator + relativeSidecarLoc;
        final List<String> sidecarFiles;
        if (new File(sidecarLoc).exists()) {
            sidecarFiles = orderedSidecarFilesFrom(sidecarLoc);
        } else {
            sidecarFiles = List.of();
        }
        final var sidecarFilesByRecordFile = sidecarFiles.stream()
                .collect(Collectors.groupingBy(
                        f -> parseSidecarFileConsensusTimeAndSequenceNo(f).getLeft(), Collectors.toList()));
        final List<RecordStreamFile> fullRecordFiles = new ArrayList<>();
        final var recordsWithSideCars = recordFiles.stream()
                .map(f -> {
                    final var recordFile = ensurePresentRecordFile(f);
                    fullRecordFiles.add(recordFile);
                    return new RecordWithSidecars(
                            recordFile,
                            sidecarFilesByRecordFile.getOrDefault(parseRecordFileConsensusTime(f), emptyList()).stream()
                                    .map(StreamFileAccess::ensurePresentSidecarFile)
                                    .toList());
                })
                .toList();
        return new RecordStreamData(recordsWithSideCars, fullRecordFiles);
    }

    public static RecordStreamFile ensurePresentRecordFile(final String f) {
        try {
            final var contents = readMaybeCompressedRecordStreamFile(f);
            if (contents.getRight().isEmpty()) {
                throw new IllegalArgumentException("No record found in " + f);
            }
            return contents.getRight().get();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read record stream file " + f, e);
        }
    }

    public static SidecarFile ensurePresentSidecarFile(final String f) {
        try {
            return RecordStreamingUtils.readMaybeCompressedSidecarFile(f);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read record stream file " + f, e);
        }
    }

    /**
     * If the given location is not already being watched, starts a new listener for it and returns
     * the listener.
     *
     * @param loc the record stream file location to watch
     * @return the listener for the given location
     * @throws Exception if there is an error starting the listener
     */
    private StreamFileAlterationListener getOrCreateListener(final String loc) throws Exception {
        if (!locationListeners.containsKey(loc)) {
            final var fAtLoc = relocatedIfNotPresentWithCurrentPathPrefix(new File(loc), "..", TEST_CLIENTS_PREFIX);
            if (!fAtLoc.exists()) {
                Files.createDirectories(fAtLoc.toPath());
            }
            locationListeners.put(loc, newValidatingListener(fAtLoc.getAbsolutePath()));
            log.info("Started stream file listener for {}", loc);
        }
        return locationListeners.get(loc);
    }

    private StreamFileAlterationListener newValidatingListener(final String loc) throws Exception {
        final var observer = new FileAlterationObserver(loc);
        final var listener = new StreamFileAlterationListener();
        observer.addListener(listener);
        monitor.addObserver(observer);
        try {
            // Will throw ISE if already started, ignore that
            monitor.start();
        } catch (IllegalStateException ignore) {
        }
        return listener;
    }
}
