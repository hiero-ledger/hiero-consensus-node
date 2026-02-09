// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCompressionUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void readsGzipWithinAuthorizedDirectory() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var gz = root.resolve("ok.gz");

        final var payload = "hello world".getBytes();
        try (final var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(payload);
        }

        final var bytes = FileCompressionUtils.readUncompressedFileBytes(root, gz.toString());
        assertArrayEquals(payload, bytes);
    }

    @Test
    void readsRelativePathWhenCwdIsWithinAuthorizedDirectory() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var gz = root.resolve("ok-relative.gz");

        final var payload = "hello world".getBytes();
        try (final var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(payload);
        }

        final var originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", root.toString());
            final var bytes = FileCompressionUtils.readUncompressedFileBytes(
                    root, gz.getFileName().toString());
            assertArrayEquals(payload, bytes);
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    @Test
    void rejectsNullInputs() {
        final var npe1 = assertThrows(
                NullPointerException.class, () -> FileCompressionUtils.readUncompressedFileBytes(null, "x"));
        assertEquals("authorizedDir must not be null", npe1.getMessage());

        final var npe2 = assertThrows(
                NullPointerException.class, () -> FileCompressionUtils.readUncompressedFileBytes(Path.of("."), null));
        assertEquals("fileLoc must not be null", npe2.getMessage());
    }

    @Test
    void rejectsRelativeTraversalOutsideAuthorizedDirectory() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);

        final var outside = tempDir.resolve("outside.gz");
        try (final var out = new GZIPOutputStream(Files.newOutputStream(outside))) {
            out.write("secret".getBytes());
        }

        assertThrows(IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, "../outside.gz"));
    }

    @Test
    void rejectsAbsolutePathOutsideAuthorizedDirectory() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);

        final var outside = tempDir.resolve("outside-abs.gz");
        try (final var out = new GZIPOutputStream(Files.newOutputStream(outside))) {
            out.write("secret".getBytes());
        }

        assertThrows(IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, outside.toString()));
    }

    @Test
    void rejectsMissingFile() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);

        assertThrows(
                IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, "does-not-exist.gz"));
    }

    @Test
    void rejectsNonRegularFilePath() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var dir = root.resolve("a-directory");
        Files.createDirectories(dir);

        final var ex = assertThrows(
                IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, dir.toString()));
        assertTrue(ex.getMessage().startsWith("Path is not a regular file:"), "Unexpected message: " + ex.getMessage());
    }

    @Test
    void rejectsUnreadableFileWhenSupported() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var gz = root.resolve("unreadable.gz");
        try (final var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("nope".getBytes());
        }

        final var supportsPosix = Files.getFileStore(gz).supportsFileAttributeView("posix")
                && Files.getFileStore(root).supportsFileAttributeView("posix");
        Assumptions.assumeTrue(supportsPosix, "POSIX permissions not supported on this filesystem");

        Files.setPosixFilePermissions(gz, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(gz), "Could not make file unreadable for this test");

        assertThrows(IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, gz.toString()));
    }

    @Test
    void wrapsInvalidGzipHeaderInIOException() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var notGzip = root.resolve("not-gzip.gz");
        Files.write(notGzip, new byte[] {0x00, 0x01, 0x02, 0x03});

        final var ex = assertThrows(
                IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, notGzip.toString()));
        assertTrue(ex.getMessage().startsWith("Error reading file "), "Unexpected message: " + ex.getMessage());
        assertNotNull(ex.getCause());
        assertTrue(
                ex.getCause().getMessage().startsWith("Invalid GZIP header for file "),
                "Unexpected cause message: " + ex.getCause().getMessage());
    }

    @Test
    void wrapsTooShortGzipFileInIOException() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var empty = root.resolve("empty.gz");
        Files.write(empty, new byte[0]);

        final var ex = assertThrows(
                IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, empty.toString()));
        assertTrue(ex.getMessage().startsWith("Error reading file "), "Unexpected message: " + ex.getMessage());
        assertNotNull(ex.getCause());
        assertTrue(
                ex.getCause().getMessage().startsWith("File is empty or too short to be a valid GZIP file: "),
                "Unexpected cause message: " + ex.getCause().getMessage());
    }

    @Test
    void wrapsFailureToResolveRealAuthorizedRootPath() throws Exception {
        final var missingRoot = tempDir.resolve("missing-root");
        final var existing = tempDir.resolve("existing.gz");
        try (final var out = new GZIPOutputStream(Files.newOutputStream(existing))) {
            out.write("payload".getBytes());
        }

        final var ex = assertThrows(
                IOException.class,
                () -> FileCompressionUtils.readUncompressedFileBytes(missingRoot, existing.toString()));
        assertTrue(
                ex.getMessage().startsWith("Failed to resolve real paths for authorized root '"),
                "Unexpected message: " + ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported() throws Exception {
        final var root = tempDir.resolve("root");
        Files.createDirectories(root);
        final var outside = tempDir.resolve("outside.gz");
        try (final var out = new GZIPOutputStream(Files.newOutputStream(outside))) {
            out.write("secret".getBytes());
        }

        final var link = root.resolve("link.gz");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (final UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symlinks not supported on this system/filesystem: " + e);
        }

        final var ex = assertThrows(
                IOException.class, () -> FileCompressionUtils.readUncompressedFileBytes(root, link.toString()));
        assertTrue(ex.getMessage().contains("outside authorized directory"), "Unexpected message: " + ex.getMessage());
    }
}
