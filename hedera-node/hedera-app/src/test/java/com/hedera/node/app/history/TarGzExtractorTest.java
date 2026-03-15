// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarGzExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsSingleFile() throws IOException {
        final byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entry("test.txt", content));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("test.txt")));
    }

    @Test
    void extractsMultipleFiles() throws IOException {
        final byte[] contentA = "file-a-content".getBytes(StandardCharsets.UTF_8);
        final byte[] contentB = "file-b-content-longer".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entry("a.txt", contentA), entry("b.txt", contentB));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(contentA, Files.readAllBytes(tempDir.resolve("a.txt")));
        assertArrayEquals(contentB, Files.readAllBytes(tempDir.resolve("b.txt")));
    }

    @Test
    void extractsDirectoryThenFile() throws IOException {
        final byte[] content = "nested".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(dirEntry("subdir/"), entry("subdir/file.txt", content));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertTrue(Files.isDirectory(tempDir.resolve("subdir")));
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("subdir/file.txt")));
    }

    @Test
    void createsParentDirectoriesImplicitly() throws IOException {
        final byte[] content = "deep".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entry("a/b/c.txt", content));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("a/b/c.txt")));
    }

    @Test
    void extractsEmptyArchive() throws IOException {
        final var tarGz = createTarGz(); // just end-of-archive markers

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        // Should succeed with no files extracted
        assertEquals(1, Files.list(tempDir).count()); // only the archive itself if written inside
    }

    @Test
    void skipsSymbolicLinks() throws IOException {
        final byte[] content = "real".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entry("real.txt", content), symlinkEntry("link.txt", "real.txt"));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("real.txt")));
        assertFalse(Files.exists(tempDir.resolve("link.txt")));
    }

    @Test
    void skipsHardLinks() throws IOException {
        final byte[] content = "real".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entry("real.txt", content), hardLinkEntry("link.txt", "real.txt"));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("real.txt")));
        assertFalse(Files.exists(tempDir.resolve("link.txt")));
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        final var tarGz = createTarGz(entry("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);

        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsAbsolutePath() throws IOException {
        final var tarGz = createTarGz(entry("/etc/passwd", "bad".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);

        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void extractsFileAlignedToBlockBoundary() throws IOException {
        // Exactly 512 bytes — no padding needed
        final byte[] content = new byte[512];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 127 + 1);
        }
        final var tarGz = createTarGz(entry("block-aligned.bin", content));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("block-aligned.bin")));
    }

    @Test
    void extractsLargerFile() throws IOException {
        // Multiple blocks worth of data
        final byte[] content = new byte[512 * 5 + 100]; // 2660 bytes
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        final var tarGz = createTarGz(entry("large.bin", content));

        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("large.bin")));
    }

    @Test
    void throwsOnNullPath() {
        assertThrows(NullPointerException.class, () -> TarGzExtractor.extract(null, tempDir));
    }

    @Test
    void throwsOnNullDestDir() {
        assertThrows(NullPointerException.class, () -> TarGzExtractor.extract(tempDir.resolve("x.tar.gz"), null));
    }

    // ===== tar archive builder helpers =====

    private static final int BLOCK_SIZE = 512;

    private Path writeTarGz(final byte[] tarGzBytes) throws IOException {
        final var path = tempDir.resolve("test.tar.gz");
        Files.write(path, tarGzBytes);
        return path;
    }

    private static byte[] createTarGz(final byte[]... entries) throws IOException {
        final var tarBaos = new ByteArrayOutputStream();
        for (final byte[] entry : entries) {
            tarBaos.write(entry);
        }
        // Two zero blocks mark end of archive
        tarBaos.write(new byte[BLOCK_SIZE]);
        tarBaos.write(new byte[BLOCK_SIZE]);

        final var gzBaos = new ByteArrayOutputStream();
        try (var gzos = new GZIPOutputStream(gzBaos)) {
            gzos.write(tarBaos.toByteArray());
        }
        return gzBaos.toByteArray();
    }

    /** Creates a tar entry (header + data blocks) for a regular file. */
    private static byte[] entry(final String name, final byte[] content) {
        return buildEntry(name, content, (byte) '0', "");
    }

    /** Creates a tar entry for a directory (no data). */
    private static byte[] dirEntry(final String name) {
        return buildEntry(name, new byte[0], (byte) '5', "");
    }

    /** Creates a tar entry for a symbolic link (no data). */
    private static byte[] symlinkEntry(final String name, final String target) {
        return buildEntry(name, new byte[0], (byte) '2', target);
    }

    /** Creates a tar entry for a hard link (no data). */
    private static byte[] hardLinkEntry(final String name, final String target) {
        return buildEntry(name, new byte[0], (byte) '1', target);
    }

    private static byte[] buildEntry(
            final String name, final byte[] content, final byte typeFlag, final String linkName) {
        final byte[] header = new byte[BLOCK_SIZE];

        // Name (0-99)
        final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // Mode (100-107): "0000644\0"
        writeOctal(header, 100, 8, 0644);

        // UID (108-115): "0000000\0"
        writeOctal(header, 108, 8, 0);

        // GID (116-123): "0000000\0"
        writeOctal(header, 116, 8, 0);

        // Size (124-135): octal, null-terminated
        writeOctal(header, 124, 12, content.length);

        // Mtime (136-147)
        writeOctal(header, 136, 12, 0);

        // Type flag (156)
        header[156] = typeFlag;

        // Link name (157-256)
        if (!linkName.isEmpty()) {
            final byte[] linkBytes = linkName.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(linkBytes, 0, header, 157, Math.min(linkBytes.length, 100));
        }

        // Magic (257-262): "ustar\0"
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);

        // Version (263-264): "00"
        header[263] = '0';
        header[264] = '0';

        // Compute checksum: sum of all bytes with checksum field (148-155) as spaces
        // First fill checksum field with spaces
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long checksum = 0;
        for (final byte b : header) {
            checksum += (b & 0xFF);
        }
        // Write checksum as 6-digit octal + null + space
        final String chkStr = String.format("%06o\0 ", checksum);
        System.arraycopy(chkStr.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 8);

        // Build the full entry: header + data + padding
        final int dataBlocks = (content.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
        final byte[] result = new byte[BLOCK_SIZE + dataBlocks * BLOCK_SIZE];
        System.arraycopy(header, 0, result, 0, BLOCK_SIZE);
        System.arraycopy(content, 0, result, BLOCK_SIZE, content.length);
        // Remaining bytes in result are already zero (padding)
        return result;
    }

    private static void writeOctal(final byte[] header, final int offset, final int fieldLen, final long value) {
        final String octal = String.format("%0" + (fieldLen - 1) + "o", value);
        final byte[] octalBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(octalBytes, 0, header, offset, octalBytes.length);
        header[offset + octalBytes.length] = 0;
    }
}
