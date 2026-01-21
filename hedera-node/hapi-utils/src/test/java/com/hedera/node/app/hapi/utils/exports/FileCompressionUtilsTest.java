// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
