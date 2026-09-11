// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * A {@link BlockSource} backed by the on-disk block-stream directory. This is the historical source
 * used when blocks are written to disk (i.e. any writer mode other than GRPC-only). It simply
 * delegates to {@link StreamFileAccess#subscribe(Path, StreamDataListener)}, preserving the existing
 * file-watching behavior (including {@link StreamDataListener#replayExistingFiles()} semantics).
 */
public class FileSystemBlockSource implements BlockSource {
    private final Path blockStreamDir;

    /**
     * Creates a file-system block source watching the given block-stream directory.
     *
     * @param blockStreamDir the block-stream directory to watch
     */
    public FileSystemBlockSource(@NonNull final Path blockStreamDir) {
        this.blockStreamDir = requireNonNull(blockStreamDir);
    }

    @NonNull
    @Override
    public Runnable subscribe(@NonNull final StreamDataListener listener) {
        requireNonNull(listener);
        return STREAM_FILE_ACCESS.subscribe(blockStreamDir, listener);
    }
}
