// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess.isBlockMarkerFile;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.isRecordFile;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.isSidecarMarkerFile;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A small utility class that listens for record stream files and provides them to any subscribed
 * listeners.
 */
public class StreamFileAlterationListener extends FileAlterationListenerAdaptor {
    private static final Logger log = LogManager.getLogger(StreamFileAlterationListener.class);

    private static final int NUM_RETRIES = 512;
    private static final long RETRY_BACKOFF_MS = 500L;

    private final List<StreamDataListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Subscribes a listener to receive record stream items.
     *
     * @param listener the listener to subscribe
     * @return a runnable that can be used to unsubscribe the listener
     */
    public Runnable subscribe(final StreamDataListener listener) {
        listeners.add(listener);
        log.info("Listener@{} subscribed {}", System.identityHashCode(this), listener.name());
        return () -> {
            listeners.remove(listener);
            log.info("Listener@{} unsubscribed {}", System.identityHashCode(this), listener.name());
        };
    }

    enum FileType {
        RECORD_STREAM_FILE,
        SIDE_CAR_FILE,
        BLOCK_FILE,
        OTHER
    }

    @Override
    public void onFileCreate(final File file) {
        switch (typeOf(file)) {
            case RECORD_STREAM_FILE -> retryExposingVia(this::exposeItems, "record", file);
            case SIDE_CAR_FILE -> retryExposingVia(this::exposeSidecars, "sidecar", file);
            case BLOCK_FILE -> retryExposingVia(this::exposeBlock, "block", file);
            case OTHER -> {
                // Nothing to expose
            }
        }
    }

    private void retryExposingVia(
            @NonNull final Consumer<File> exposure, @NonNull final String fileType, @NonNull final File f) {
        var retryCount = 0;
        while (true) {
            retryCount++;
            try {
                exposure.accept(f);
                log.info(
                        "Listener@{} gave validators access to {} file {}",
                        System.identityHashCode(this),
                        fileType,
                        f.getAbsolutePath());
                return;
            } catch (Exception e) {
                if (retryCount < NUM_RETRIES) {
                    try {
                        MILLISECONDS.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    // Don't fail hard on an empty file; if a test really depends on the contents of a
                    // pending stream file, it will fail anyways---and if this is just a timing condition,
                    // no reason to destabilize the PR check
                    if (f.length() > 0) {
                        log.error("Could not expose contents of {} file {}", fileType, f.getAbsolutePath(), e);
                        throw new IllegalStateException();
                    }
                }
            }
        }
    }

    private void exposeBlock(@NonNull final File file) {
        // Get Block file path using marker file path
        final var markerFilePath = file.toPath();
        final var blockFileName = file.getName().replace(".mf", "");

        // Check for compressed file first (.blk.gz)
        final var compressedBlockFilePath = markerFilePath.resolveSibling(blockFileName + ".blk.gz");
        final var uncompressedBlockFilePath = markerFilePath.resolveSibling(blockFileName + ".blk");

        // Determine which block file exists - compressed or uncompressed
        final var blockFilePath =
                Files.exists(compressedBlockFilePath) ? compressedBlockFilePath : uncompressedBlockFilePath;

        final var block =
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockFilePath).getFirst();
        listeners.forEach(l -> {
            try {
                l.onNewBlock(block);
            } catch (Exception e) {
                log.error("{} failed to process block file {}", l, file.getAbsolutePath(), e);
            }
        });
    }

    private void exposeSidecars(final File file) {
        // Get Sidecar file path using marker file path
        final var markerPath = file.toPath();
        final var baseName = file.getName().replace(".mf", "");
        final var gzPath = markerPath.resolveSibling(baseName + ".rcd.gz");
        final var plainPath = markerPath.resolveSibling(baseName + ".rcd");
        final var sidecarPath = Files.exists(gzPath) ? gzPath : plainPath;

        final var contents = StreamFileAccess.ensurePresentSidecarFile(sidecarPath.toString());
        contents.getSidecarRecordsList().forEach(sidecar -> listeners.forEach(l -> l.onNewSidecar(sidecar)));
    }

    private void exposeItems(final File file) {
        final var contents = StreamFileAccess.ensurePresentRecordFile(file.getAbsolutePath());
        contents.getRecordStreamItemsList().forEach(item -> listeners.forEach(l -> l.onNewItem(item)));
    }

    public int numListeners() {
        return listeners.size();
    }

    private FileType typeOf(final File file) {
        // Ignore empty files, which are likely to be in the process of being written
        if (isBlockMarkerFile(file)) {
            return FileType.BLOCK_FILE;
        } else if (isSidecarMarkerFile(file.getName())) {
            return FileType.SIDE_CAR_FILE;
        } else if (file.length() == 0L) {
            return FileType.OTHER;
        } else if (isRecordFile(file.getName())) {
            return FileType.RECORD_STREAM_FILE;
        } else {
            return FileType.OTHER;
        }
    }
}
