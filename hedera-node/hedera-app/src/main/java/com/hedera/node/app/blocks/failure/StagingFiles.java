// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
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

    /** A reconstructed ISS-round block written from the in-memory buffer in {@code GRPC} mode. */
    static final String ISS_BLOCK_EXT = ".iss.zstd";

    /**
     * Every staged file ends with this; the uploader instance is set to this single extension. The block files the
     * writers produce ({@code .blk.zstd}, {@code .pnd.zstd}, {@code .open.zstd}) already do, so they are staged as is.
     */
    static final String ZSTD_EXT = ".zstd";
    /**
     * Upload marker. Outside our control: the uploader (mirror.py in marker mode, set up by DevOps) uploads
     * {@code <name>.zstd} once {@code <name>.mf} exists, verifies it, then deletes both. It must run with
     * {@code SIG_EXTENSION=mf}, {@code STREAM_EXTENSION=zstd} and {@code SIG_REQUIRE=true}; without the last one it
     * deletes unmarked files without uploading them.
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
     * Stages a block contents file into {@code stageDir} atomically and returns the staged path. A file that already
     * ends in {@code .zstd} is copied as is. Any other file (a legacy {@code .gz} block file left by an older version)
     * is Zstandard-wrapped, with {@code .zstd} appended to its name, so the single {@code STREAM_EXTENSION=zstd}
     * uploader instance ships it too.
     */
    static Path stageContents(@NonNull final Path src, @NonNull final Path stageDir) throws IOException {
        final String name = src.getFileName().toString();
        if (name.endsWith(ZSTD_EXT)) {
            final Path dest = stageDir.resolve(name);
            atomicCopy(src, dest);
            return dest;
        }
        final Path dest = stageDir.resolve(name + ZSTD_EXT);
        atomicZstdCopy(src, dest);
        return dest;
    }

    /**
     * Copies {@code src} to {@code dest} Zstandard-compressed and atomically. Used for files that do not end in
     * {@code .zstd} (the {@code .pnd.json} proof sidecar and legacy {@code .gz} block files) so the single
     * {@code STREAM_EXTENSION=zstd} uploader instance ships them too.
     */
    static void atomicZstdCopy(@NonNull final Path src, @NonNull final Path dest) throws IOException {
        final Path tmp = tmpFor(dest);
        try {
            try (final OutputStream out = BlockFileUtils.newZstdOutputStream(Files.newOutputStream(tmp))) {
                Files.copy(src, out);
            }
            atomicMoveOnto(tmp, dest);
        } catch (final IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** The staged (Zstandard-compressed) name of a proof sidecar. */
    static String stagedSidecarName(@NonNull final Path sidecar) {
        return sidecar.getFileName() + ZSTD_EXT;
    }

    /**
     * Writes an empty {@code <name>.mf} upload marker next to every staged {@code <name>.zstd} in {@code dir}. Call
     * only once the files are in place: the uploader drops a marker whose file is missing. Best-effort; never throws.
     */
    static void writeUploadMarkers(@NonNull final Path dir) {
        try (final Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(ZSTD_EXT)).forEach(StagingFiles::writeUploadMarker);
        } catch (final IOException | UncheckedIOException e) {
            log.warn("Could not list {} to write upload markers", dir, e);
        }
    }

    private static void writeUploadMarker(@NonNull final Path staged) {
        final String name = staged.getFileName().toString();
        final Path marker =
                staged.resolveSibling(name.substring(0, name.length() - ZSTD_EXT.length()) + UPLOAD_MARKER_EXT);
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
