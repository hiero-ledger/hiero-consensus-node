// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helpers for staging captured ISS artifacts into the bind-mounted directory the deployment's uploader watches: the
 * shared per-incident directory layout, the atomic write used everywhere, and the upload markers.
 *
 * <p>Every artifact is written to a {@code .tmp} sibling and atomically renamed into place. Once a whole capture is in
 * place, an empty {@code .mf} marker per file tells the uploader the file is ready to ship.
 */
final class StagingFiles {
    private static final Logger log = LogManager.getLogger(StagingFiles.class);

    /** Suffix for the temporary file an artifact is written to before its atomic rename. */
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

    /** Every staged file ends with this; the uploader instance is set to this single extension. */
    static final String GZ_EXT = ".gz";
    /**
     * Upload marker. Outside our control: the uploader (mirror.py in marker mode, set up by DevOps) uploads
     * {@code <name>.gz} once {@code <name>.mf} exists, verifies it, then deletes both. It must run with
     * {@code SIG_EXTENSION=mf}, {@code STREAM_EXTENSION=gz} and {@code SIG_REQUIRE=true}; without the last one it deletes
     * unmarked files without uploading them.
     */
    static final String UPLOAD_MARKER_EXT = ".mf";

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
     * Copies {@code src} to {@code dest} gzipped and atomically. Used for the {@code .pnd.json} proof sidecar so the
     * single {@code STREAM_EXTENSION=gz} uploader instance ships it too.
     */
    static void atomicGzipCopy(@NonNull final Path src, @NonNull final Path dest) throws IOException {
        final Path tmp = tmpFor(dest);
        try {
            try (final OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                Files.copy(src, out);
            }
            atomicMoveOnto(tmp, dest);
        } catch (final IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** The staged (gzipped) name of a proof sidecar. */
    static String stagedSidecarName(@NonNull final Path sidecar) {
        return sidecar.getFileName() + GZ_EXT;
    }

    /**
     * Writes an empty {@code <name>.mf} upload marker next to every staged {@code <name>.gz} in {@code dir}. Call only
     * once the files are in place: the uploader drops a marker whose file is missing. Best-effort; never throws.
     */
    static void writeUploadMarkers(@NonNull final Path dir) {
        try (final Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(GZ_EXT)).forEach(StagingFiles::writeUploadMarker);
        } catch (final IOException | UncheckedIOException e) {
            log.warn("Could not list {} to write upload markers", dir, e);
        }
    }

    private static void writeUploadMarker(@NonNull final Path staged) {
        final String name = staged.getFileName().toString();
        final Path marker =
                staged.resolveSibling(name.substring(0, name.length() - GZ_EXT.length()) + UPLOAD_MARKER_EXT);
        try {
            Files.write(marker, new byte[0]);
        } catch (final IOException e) {
            log.warn("Could not write upload marker {}; {} stays on disk and is not uploaded", marker, staged, e);
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
