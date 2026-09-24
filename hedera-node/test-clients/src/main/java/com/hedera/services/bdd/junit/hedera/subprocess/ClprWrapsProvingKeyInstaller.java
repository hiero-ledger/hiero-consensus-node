// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provisions the WRAPS proving-key artifacts a cold-path subprocess node needs, once per test JVM.
 *
 * <p>Given the artifacts directory, this extracts the sibling {@code wraps*.tar.gz} archive when the
 * {@code *.bin} files are absent, then writes the archive's SHA-384 to {@value #HASH_FILE_NAME} (which the
 * runtime's readiness check requires) when that file is absent. It is a no-op when both are already present,
 * and hashes the real archive rather than echoing config so a wrong archive still fails verification. The
 * archive is located by glob (not by the directory name) so the version lives only in the published tarball,
 * not in the extracted directory name.
 */
public final class ClprWrapsProvingKeyInstaller {
    private static final Logger log = LogManager.getLogger(ClprWrapsProvingKeyInstaller.class);

    /** The proving-key artifacts the native WRAPS library maps; the archive holds exactly these, flat. */
    private static final Set<String> REQUIRED_ARTIFACT_FILES =
            Set.of("decider_pp.bin", "decider_vp.bin", "nova_pp.bin", "nova_vp.bin");

    /** The hash file the runtime's readiness check consults; holds the bare hex archive hash. */
    static final String HASH_FILE_NAME = "wraps.sha384";

    /** Dirs already provisioned in this JVM (fast path), and per-dir locks to serialize the first pass. */
    private static final Set<Path> DONE = ConcurrentHashMap.newKeySet();

    private static final ConcurrentMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private ClprWrapsProvingKeyInstaller() {}

    /**
     * Ensures the WRAPS artifacts directory is extracted and carries its {@value #HASH_FILE_NAME} file,
     * doing the work at most once per directory per JVM. Best-effort: a directory with nothing to
     * provision (no {@code .bin} files and no sibling archive) is left untouched so any other provisioning
     * convention (e.g. the WRAPS-download PR-check suite) is preserved.
     *
     * @param artifactsDir the directory {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} will point at
     */
    public static void ensureProvisioned(@NonNull final Path artifactsDir) {
        final Path dir = artifactsDir.toAbsolutePath().normalize();
        if (DONE.contains(dir)) {
            return;
        }
        final Object lock = LOCKS.computeIfAbsent(dir, k -> new Object());
        synchronized (lock) {
            if (DONE.contains(dir)) {
                return;
            }
            provision(dir);
            DONE.add(dir);
        }
    }

    private static void provision(@NonNull final Path dir) {
        final Path archive = findArchive(dir);
        if (!binariesPresent(dir)) {
            if (archive == null) {
                // Nothing to do: neither extracted artifacts nor an archive to extract. Leave it for the
                // runtime to report if this path actually needed WRAPS (or for another provisioning path).
                return;
            }
            extractFlat(archive, dir);
            if (!binariesPresent(dir)) {
                throw new IllegalStateException(
                        "WRAPS archive " + archive + " did not yield " + REQUIRED_ARTIFACT_FILES + " in " + dir);
            }
        }
        final Path hashFile = dir.resolve(HASH_FILE_NAME);
        if (Files.exists(hashFile)) {
            return;
        }
        if (archive == null) {
            log.warn(
                    "WRAPS artifacts present in {} but {} is missing and there is no sibling wraps*.tar.gz "
                            + "archive to derive it from; the recursive prover may not become ready",
                    dir,
                    HASH_FILE_NAME);
            return;
        }
        final String hashHex = sha384Hex(archive);
        writeStringAtomically(hashFile, hashHex);
        log.info("Provisioned WRAPS artifacts hash file {} ({}) from {}", hashFile, hashHex, archive);
    }

    /**
     * Locates the WRAPS archive as a {@code wraps*.tar.gz} file sibling to the artifacts directory, so the
     * version lives only in the published tarball name and not in the directory name. Returns the
     * lexicographically-highest match (newest version) when several are present, or null when none are.
     */
    @Nullable
    private static Path findArchive(@NonNull final Path dir) {
        final Path parent = dir.toAbsolutePath().getParent();
        if (parent == null) {
            return null;
        }
        Path match = null;
        try (var stream = Files.newDirectoryStream(parent, "wraps*.tar.gz")) {
            for (final Path candidate : stream) {
                final boolean newer = match == null
                        || candidate
                                        .getFileName()
                                        .toString()
                                        .compareTo(match.getFileName().toString())
                                > 0;
                if (Files.isRegularFile(candidate) && newer) {
                    match = candidate;
                }
            }
        } catch (final IOException e) {
            return null;
        }
        return match;
    }

    private static boolean binariesPresent(@NonNull final Path dir) {
        for (final var name : REQUIRED_ARTIFACT_FILES) {
            final Path bin = dir.resolve(name);
            try {
                if (!Files.isRegularFile(bin) || Files.size(bin) == 0) {
                    return false;
                }
            } catch (final IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts a flat {@code .tar.gz} (entries are the artifact files at the archive root) into {@code dir},
     * writing each entry to a temp file and atomically moving it into place so an interrupted run never
     * leaves a truncated artifact that later looks complete.
     */
    private static void extractFlat(@NonNull final Path archive, @NonNull final Path dir) {
        log.info("Extracting WRAPS artifacts from {} into {}", archive, dir);
        try {
            Files.createDirectories(dir);
            try (InputStream fis = Files.newInputStream(archive);
                    BufferedInputStream bis = new BufferedInputStream(fis);
                    GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
                    TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {
                TarArchiveEntry entry;
                while ((entry = tais.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    final String name = Path.of(entry.getName()).getFileName().toString();
                    if (!REQUIRED_ARTIFACT_FILES.contains(name)) {
                        continue;
                    }
                    final Path out = dir.resolve(name);
                    final Path tmp = dir.resolve(name + ".part");
                    try (var os = Files.newOutputStream(tmp)) {
                        tais.transferTo(os);
                    }
                    try {
                        Files.move(tmp, out, REPLACE_EXISTING, ATOMIC_MOVE);
                    } catch (final IOException atomicUnsupported) {
                        Files.move(tmp, out, REPLACE_EXISTING);
                    }
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to extract WRAPS artifacts from " + archive, e);
        }
    }

    private static String sha384Hex(@NonNull final Path file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-384");
            try (InputStream is = Files.newInputStream(file);
                    BufferedInputStream bis = new BufferedInputStream(is)) {
                final byte[] buffer = new byte[1 << 20];
                int read;
                while ((read = bis.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to hash " + file, e);
        }
    }

    private static void writeStringAtomically(@NonNull final Path target, @NonNull final String content) {
        try {
            final Path tmp = target.resolveSibling(target.getFileName().toString() + ".part");
            Files.write(tmp, List.of(content));
            // Files.write with a single line adds a trailing newline; the runtime reads with .trim(),
            // so this is harmless, and an atomic move keeps a partial read from ever seeing the file.
            try {
                Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (final IOException atomicUnsupported) {
                Files.move(tmp, target, REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to write " + target, e);
        }
    }
}
