// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Helpers for staging captured ISS artifacts into the bind-mounted directory the deployment's uploader watches: the
 * shared per-incident directory layout and the atomic write used everywhere.
 *
 * <p>Every artifact is made visible under its final name via an atomic rename, so the separate uploader process (which
 * matches by file extension) can never observe a half-written file: we write to a {@code .tmp} sibling first — which the
 * uploader's extension filter ignores — then atomically move it into place.
 */
final class StagingFiles {
    /** Suffix for the temporary file; the uploader matches final extensions (e.g. {@code .gz}), so it skips this. */
    static final String TMP_SUFFIX = ".tmp";

    // On-disk block-artifact extensions written by the block-item writers, centralized here for the staging pipeline.
    /** A completed, closed block's gzipped contents. */
    static final String COMPLETE_EXT = ".blk.gz";
    /** A pending block's gzipped contents. */
    static final String PENDING_EXT = ".pnd.gz";
    /** A pending block's proof sidecar, staged alongside its {@link #PENDING_EXT} contents. */
    static final String PENDING_PROOF_EXT = ".pnd.json";
    /** An open, unproven block flushed to disk for triage at catastrophic failure. */
    static final String INCOMPLETE_EXT = ".open.gz";
    /** A reconstructed ISS-round block written from the in-memory buffer in {@code GRPC} mode. */
    static final String ISS_BLOCK_EXT = ".iss.gz";
    /** {@code FileBlockItemWriter}'s completion marker; a {@link #COMPLETE_EXT} is finished only once this exists. */
    static final String COMPLETION_MARKER_EXT = ".mf";
    /** The plain-text pointer staged as a last resort when a {@code GRPC} ISS block is no longer buffered. */
    static final String POINTER_EXT = ".txt";

    /** Per-incident folder name: a UTC timestamp, key-safe and lexicographically sortable. */
    private static final DateTimeFormatter INCIDENT_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private StagingFiles() {}

    /** A fresh per-incident folder name from {@code instantSource} (a UTC timestamp). */
    static String incidentFolderNow(@NonNull final InstantSource instantSource) {
        return INCIDENT_FOLDER_FORMAT.format(instantSource.instant());
    }

    /** The per-incident staging dir {@code {issBlockDir}/block-{nodeAccount}/{incidentFolder}}. */
    static Path incidentDir(
            @NonNull final FileSystem fileSystem,
            @NonNull final String issBlockDir,
            @NonNull final String nodeAccount,
            @NonNull final String incidentFolder) {
        return fileSystem.getPath(issBlockDir).resolve("block-" + nodeAccount).resolve(incidentFolder);
    }

    /** Copies {@code src} to {@code dest} atomically: copy to {@code dest.tmp}, then rename onto {@code dest}. */
    static void atomicCopy(@NonNull final Path src, @NonNull final Path dest) throws IOException {
        final Path tmp = tmpFor(dest);
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            atomicMoveOnto(tmp, dest);
        } catch (final IOException e) {
            // Do not leave a partial/orphaned .tmp behind on a failed copy or rename.
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Renames an already-written {@code tmp} file onto {@code dest} atomically, falling back to a plain replace on the
     * (rare) file systems that do not support atomic moves.
     */
    static void atomicMoveOnto(@NonNull final Path tmp, @NonNull final Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The {@code .tmp} sibling used to stage {@code dest} before its atomic rename. */
    static Path tmpFor(@NonNull final Path dest) {
        return dest.resolveSibling(dest.getFileName() + TMP_SUFFIX);
    }
}
