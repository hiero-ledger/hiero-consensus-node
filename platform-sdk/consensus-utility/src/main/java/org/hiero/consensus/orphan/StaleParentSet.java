// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.orphan;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Loads the set of "stale" parent event hashes used only during historical block-stream replay.
 *
 * <p>A stale event is one that was referenced as a parent by some consensus event but never reached consensus itself
 * (it went ancient before consensus) and is therefore absent from the block stream. During replay of an old stream,
 * such a parent can never arrive, so {@link DefaultOrphanBuffer} must treat it as permanently absent to avoid a
 * deadlock. The set of such hashes is produced by {@code BlocksToPcesWorkflow} while generating the PCES and written
 * to a sidecar file ({@value #SIDECAR_FILE_NAME}) in the PCES directory, one lowercase 96-character (SHA-384) hex
 * hash per line.
 *
 * <p><b>Directory source.</b> The PCES directory to read the sidecar from is named by the
 * {@value #PCES_REPLAY_DIR_PROPERTY} system property. When set, {@link #loadForReplay()} looks for
 * {@value #SIDECAR_FILE_NAME} inside it. When unset (normal operation), it returns an empty set and the orphan buffer
 * behaves exactly as before. The property is read from the JVM at intake construction time, so there is no dependency
 * on module initialization order.
 */
public final class StaleParentSet {

    /** Conventional sidecar file name, expected in the PCES directory alongside the .pces files. */
    public static final String SIDECAR_FILE_NAME = "stale-parents.txt";

    /**
     * System property naming the PCES directory being replayed. When set, {@link #loadForReplay()} looks for
     * {@value #SIDECAR_FILE_NAME} inside it. Unset in normal operation (returns an empty set).
     */
    public static final String PCES_REPLAY_DIR_PROPERTY = "pces.replayDir";

    private static final int SHA_384_HEX_LENGTH = 96;

    private StaleParentSet() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Load the stale-parent set for replay: if the {@value #PCES_REPLAY_DIR_PROPERTY} system property names a PCES
     * directory containing a {@value #SIDECAR_FILE_NAME} sidecar, return its contents; otherwise return an empty set.
     * This is the entry point called from the event-intake wiring. It is inert in normal operation (property unset or
     * no sidecar present), so the orphan buffer behaves exactly as before.
     *
     * @return the set of stale parent hashes, empty if none configured/present
     */
    @NonNull
    public static Set<Hash> loadForReplay() {
        final String dir = System.getProperty(PCES_REPLAY_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return Set.of();
        }
        return loadFromPcesDir(Path.of(dir.trim()));
    }

    /**
     * Load the stale-parent set from {@value #SIDECAR_FILE_NAME} in the given PCES directory, if present. Returns an
     * empty set if {@code pcesDir} is {@code null} or the sidecar does not exist.
     *
     * @param pcesDir the PCES directory
     * @return the set of stale parent hashes, empty if no sidecar is present
     */
    @NonNull
    public static Set<Hash> loadFromPcesDir(@Nullable final Path pcesDir) {
        if (pcesDir == null) {
            return Set.of();
        }
        final Path sidecar = pcesDir.resolve(SIDECAR_FILE_NAME);
        if (!Files.isRegularFile(sidecar)) {
            return Set.of();
        }
        return load(sidecar);
    }

    /**
     * Load the stale-parent set from the given file. Each non-blank line must be a 96-character SHA-384 hex hash.
     *
     * @param file the sidecar file
     * @return the set of stale parent hashes
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if any line is not a valid 96-character hex hash
     */
    @NonNull
    public static Set<Hash> load(@NonNull final Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read stale-parents file: " + file, e);
        }
        final Set<Hash> result = new HashSet<>();
        int lineNo = 0;
        for (final String raw : lines) {
            lineNo++;
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() != SHA_384_HEX_LENGTH) {
                throw new IllegalArgumentException("Invalid stale-parent hash at " + file + ":" + lineNo
                        + " (expected " + SHA_384_HEX_LENGTH + " hex chars, got " + line.length() + ")");
            }
            result.add(new Hash(hexToBytes(line, file, lineNo), DigestType.SHA_384));
        }
        return Set.copyOf(result);
    }

    private static byte[] hexToBytes(@NonNull final String hex, @NonNull final Path file, final int lineNo) {
        final int len = hex.length();
        final byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            final int hi = Character.digit(hex.charAt(i), 16);
            final int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException(
                        "Non-hex character in stale-parent hash at " + file + ":" + lineNo);
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}