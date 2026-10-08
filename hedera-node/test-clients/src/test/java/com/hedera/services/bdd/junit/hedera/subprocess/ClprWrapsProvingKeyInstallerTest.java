// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.subprocess.ClprWrapsProvingKeyInstaller.HASH_FILE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ClprWrapsProvingKeyInstaller}. Each test uses its own temp dir, since provisioning is
 * memoized per directory for the lifetime of the JVM.
 */
class ClprWrapsProvingKeyInstallerTest {
    private static final List<String> ARTIFACTS =
            List.of("decider_pp.bin", "decider_vp.bin", "nova_pp.bin", "nova_vp.bin");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Extracts the sibling archive and writes its SHA-384 hash file")
    void extractsArchiveAndWritesHash() throws Exception {
        final var archive = createArchive(tempDir.resolve("wraps-v1.0.0.tar.gz"), ARTIFACTS);
        final var artifactsDir = tempDir.resolve("wraps");

        ClprWrapsProvingKeyInstaller.ensureProvisioned(artifactsDir);

        for (final var name : ARTIFACTS) {
            assertEquals("content of " + name, Files.readString(artifactsDir.resolve(name)));
        }
        assertEquals(
                sha384Hex(archive),
                Files.readString(artifactsDir.resolve(HASH_FILE_NAME)).trim());
        try (var leftovers = Files.list(artifactsDir)) {
            assertFalse(
                    leftovers.anyMatch(p -> p.getFileName().toString().startsWith(".extract-")),
                    "staging dir must be cleaned up");
        }
    }

    @Test
    @DisplayName("Fails when the archive does not contain every required artifact")
    void failsOnIncompleteArchive() throws Exception {
        createArchive(tempDir.resolve("wraps-v1.0.0.tar.gz"), ARTIFACTS.subList(0, 2));

        assertThrows(
                IllegalStateException.class,
                () -> ClprWrapsProvingKeyInstaller.ensureProvisioned(tempDir.resolve("wraps")));
    }

    @Test
    @DisplayName("Does nothing when there are neither artifacts nor an archive")
    void noOpWithoutArtifactsOrArchive() {
        final var artifactsDir = tempDir.resolve("wraps");

        ClprWrapsProvingKeyInstaller.ensureProvisioned(artifactsDir);

        assertFalse(Files.exists(artifactsDir));
    }

    /** Builds a flat {@code .tar.gz} (entries at the archive root) with the system {@code tar}. */
    private Path createArchive(final Path archive, final List<String> entries) throws Exception {
        final var source = Files.createDirectories(tempDir.resolve("archive-source"));
        for (final var name : entries) {
            Files.writeString(source.resolve(name), "content of " + name);
        }
        final List<String> command =
                new ArrayList<>(List.of("tar", "-czf", archive.toString(), "-C", source.toString()));
        command.addAll(entries);
        final var process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IOException("tar failed to create " + archive);
        }
        return archive;
    }

    private static String sha384Hex(final Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-384").digest(Files.readAllBytes(file)));
    }
}
