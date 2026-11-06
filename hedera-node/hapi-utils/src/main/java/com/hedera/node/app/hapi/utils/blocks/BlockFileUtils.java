// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/** File naming and compression shared by block stream writers and readers. */
public final class BlockFileUtils {
    private static final List<String> COMPLETE_SUFFIXES = List.of(".blk.zstd", ".blk.gz", ".blk");
    private static final List<String> PENDING_SUFFIXES = List.of(".pnd.zstd", ".pnd.gz", ".pnd");

    private BlockFileUtils() {}

    /**
     * Returns the 19-digit, zero-padded representation of a nonnegative block number.
     *
     * @param blockNumber the block number
     * @return the numeric filename stem
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String blockNumberToFileName(final long blockNumber) {
        if (blockNumber < 0) {
            throw new IllegalArgumentException("Block number must be nonnegative");
        }
        return String.format(Locale.ROOT, "%019d", blockNumber);
    }

    /**
     * Returns the name of a completed block file.
     *
     * @param blockNumber the block number
     * @return the filename ending in {@code .blk.zstd}
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String blockFileName(final long blockNumber) {
        return blockNumberToFileName(blockNumber) + ".blk.zstd";
    }

    /**
     * Returns the name of a block awaiting its proof.
     *
     * @param blockNumber the block number
     * @return the filename ending in {@code .pnd.zstd}
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String pendingFileName(final long blockNumber) {
        return blockNumberToFileName(blockNumber) + ".pnd.zstd";
    }

    /**
     * Returns the name of a block still being written.
     *
     * @param blockNumber the block number
     * @return the filename ending in {@code .open.zstd}
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String incompleteFileName(final long blockNumber) {
        return blockNumberToFileName(blockNumber) + ".open.zstd";
    }

    /**
     * Returns the name of a completed block's marker file.
     *
     * @param blockNumber the block number
     * @return the filename ending in {@code .mf}
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String markerFileName(final long blockNumber) {
        return blockNumberToFileName(blockNumber) + ".mf";
    }

    /**
     * Returns the name of a pending block's proof file.
     *
     * @param blockNumber the block number
     * @return the filename ending in {@code .pnd.json}
     * @throws IllegalArgumentException if the block number is negative
     */
    public static String pendingProofFileName(final long blockNumber) {
        return blockNumberToFileName(blockNumber) + ".pnd.json";
    }

    /**
     * Checks whether the path has a supported completed-block suffix, without checking file existence.
     *
     * @param path the path to check
     * @return whether its filename ends in {@code .blk.zstd}, {@code .blk.gz}, or {@code .blk}
     */
    public static boolean isCompleteBlockFile(@NonNull final Path path) {
        final var name = path.getFileName().toString();
        return COMPLETE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * Checks whether the path has a supported pending-block suffix, without checking file existence.
     *
     * @param path the path to check
     * @return whether its filename ends in {@code .pnd.zstd}, {@code .pnd.gz}, or {@code .pnd}
     */
    public static boolean isPendingBlockFile(@NonNull final Path path) {
        final var name = path.getFileName().toString();
        return PENDING_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * Returns the exact same-stem marker path for a supported completed block.
     *
     * @param blockPath the completed block's path
     * @return the sibling path ending in {@code .mf}
     * @throws IllegalArgumentException if the path lacks a supported completed-block suffix
     */
    public static Path markerPathFor(@NonNull final Path blockPath) {
        return replaceSuffix(blockPath, COMPLETE_SUFFIXES, ".mf");
    }

    /**
     * Returns the exact same-stem proof path for a supported pending block.
     *
     * @param blockPath the pending block's path
     * @return the sibling path ending in {@code .pnd.json}
     * @throws IllegalArgumentException if the path lacks a supported pending-block suffix
     */
    public static Path pendingProofPathFor(@NonNull final Path blockPath) {
        return replaceSuffix(blockPath, PENDING_SUFFIXES, ".pnd.json");
    }

    /**
     * Finds the same-stem block for a marker, preferring Zstandard, then gzip, then plain files.
     *
     * @param markerPath the marker path ending in {@code .mf}
     * @return the first matching regular block file, or empty if none exists or the marker suffix is unsupported
     */
    public static Optional<Path> findBlockFileForMarker(@NonNull final Path markerPath) {
        return findSibling(markerPath, ".mf", COMPLETE_SUFFIXES);
    }

    /**
     * Finds the same-stem pending block for a proof, preferring Zstandard, then gzip, then plain files.
     *
     * @param proofPath the pending proof path ending in {@code .pnd.json}
     * @return the first matching regular pending file, or empty if none exists or the proof suffix is unsupported
     */
    public static Optional<Path> findPendingFileForProof(@NonNull final Path proofPath) {
        return findSibling(proofPath, ".pnd.json", PENDING_SUFFIXES);
    }

    /**
     * Opens a file, decompressing Zstandard or gzip according to its suffix.
     * The file stream is closed if decompressor construction fails.
     *
     * @param path the file to open
     * @return the input stream, owned by the caller
     * @throws IOException if opening the file or constructing its decompressor fails
     */
    public static InputStream openInputStream(@NonNull final Path path) throws IOException {
        final var input = Files.newInputStream(path);
        try {
            final var name = path.getFileName().toString();
            if (name.endsWith(".zstd")) {
                return new ZstdInputStreamNoFinalizer(input);
            }
            if (name.endsWith(".gz")) {
                return new GZIPInputStream(input);
            }
            return input;
        } catch (IOException | RuntimeException | Error e) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Wraps an output stream in a level-3 Zstandard encoder with frame checksums enabled.
     * The output is closed if constructing or configuring the encoder fails.
     *
     * @param output the output stream, whose ownership transfers to the encoder
     * @return the encoder, owned by the caller
     * @throws IOException if constructing or configuring the encoder fails
     */
    public static OutputStream newZstdOutputStream(@NonNull final OutputStream output) throws IOException {
        OutputStream ownedOutput = output;
        try {
            final var compressed = new ZstdOutputStreamNoFinalizer(output, 3);
            ownedOutput = compressed;
            return compressed.setChecksum(true);
        } catch (IOException | RuntimeException | Error e) {
            try {
                ownedOutput.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static Optional<Path> findSibling(
            final Path path, final String suffix, final List<String> replacementSuffixes) {
        final var name = path.getFileName().toString();
        if (!name.endsWith(suffix)) {
            return Optional.empty();
        }
        final var stem = name.substring(0, name.length() - suffix.length());
        return replacementSuffixes.stream()
                .map(replacement -> path.resolveSibling(stem + replacement))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private static Path replaceSuffix(final Path path, final List<String> suffixes, final String replacement) {
        final var name = path.getFileName().toString();
        for (final var suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return path.resolveSibling(name.substring(0, name.length() - suffix.length()) + replacement);
            }
        }
        throw new IllegalArgumentException("Unsupported block file: " + path);
    }
}
