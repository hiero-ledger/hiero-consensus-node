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
import java.util.Arrays;
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
        final var tarGz = createTarGz(); // should only have end-of-archive markers

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
        // Multiple blocks' worth of data
        // Multiple blocks' worth of data
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

    @Test
    void rejectsEmptyEntryName() throws IOException {
        final var tarGz = createTarGz(entry("", new byte[0]));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsNameTooLong() throws IOException {
        final var longName = "x".repeat(4097);
        final var tarGz = createTarGz(gnuLongNameEntry(longName, "data".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsControlCharacterInName() throws IOException {
        final var tarGz = createTarGz(entry("file\ttab.txt", "x".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsBackslashInName() throws IOException {
        final var tarGz = createTarGz(entry("dir\\file.txt", "x".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsWindowsAbsolutePath() throws IOException {
        // Use forward slash so the backslash check doesn't fire first
        final var tarGz = createTarGz(entryNoMagic("C:/foo.txt", "x".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsBareDotDotName() throws IOException {
        final var tarGz = createTarGz(dirEntry(".."));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsMiddlePathTraversal() throws IOException {
        final var tarGz = createTarGz(entry("foo/../bar/f.txt", "x".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsTrailingPathTraversal() throws IOException {
        final var tarGz = createTarGz(dirEntry("foo/bar/.."));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsExcessiveNestingDepth() throws IOException {
        // 22 path components exceeds MAX_DEPTH of 20
        final var deepName = "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v.txt";
        final var tarGz = createTarGz(entry(deepName, "x".getBytes(StandardCharsets.UTF_8)));
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void rejectsTooManyEntries() throws IOException {
        final byte[][] entries = new byte[10_001][];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("same.txt", new byte[0]);
        }
        final var tarGz = createTarGz(entries);
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void extractsGnuLongNameEntry() throws IOException {
        // 110 chars — exceeds the 100-byte tar name field but stays under MAX_DEPTH
        final var longName = "subdir/" + "a".repeat(103);
        final byte[] content = "long name content".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(gnuLongNameEntry(longName, content));
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve(longName)));
    }

    @Test
    void rejectsTruncatedGnuLongName() throws IOException {
        // Type 'L' header claiming 200 bytes of name, but stream ends after only 50
        final byte[] lHeader = buildRawHeader("././@LongLink", 200, (byte) 'L');
        final byte[] partialName = new byte[50];
        Arrays.fill(partialName, (byte) 'x');
        final var tarGz = gzipRaw(lHeader, partialName);
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void skipsPaxHeaders() throws IOException {
        final byte[] content = "real".getBytes(StandardCharsets.UTF_8);
        final byte[] paxData = "20 path=extended.txt\n".getBytes(StandardCharsets.US_ASCII);
        for (final byte typeFlag : new byte[] {'x', 'g', 'X'}) {
            final var tarGz = createTarGz(paxEntry(typeFlag, paxData), entry("file.txt", content));
            final var archive = writeTarGz(tarGz);
            TarGzExtractor.extract(archive, tempDir);
            assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("file.txt")));
            Files.delete(tempDir.resolve("file.txt"));
        }
    }

    @Test
    void parsesGnuBinarySizeEncoding() throws IOException {
        final byte[] content = "binary-size".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entryWithBinarySize("binary.txt", content));
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("binary.txt")));
    }

    @Test
    void parsesEmptyOctalSizeAsZero() throws IOException {
        final var tarGz = createTarGz(entryWithEmptySize("empty-size.txt"));
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertEquals(0, Files.size(tempDir.resolve("empty-size.txt")));
    }

    @Test
    void parsesNonUstarNameWithoutPrefix() throws IOException {
        final byte[] content = "no-ustar".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entryNoMagic("plain.txt", content));
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("plain.txt")));
    }

    @Test
    void parsesUstarPrefixPlusName() throws IOException {
        final byte[] content = "prefixed".getBytes(StandardCharsets.UTF_8);
        final var tarGz = createTarGz(entryWithPrefix("some/prefix", "file.txt", content));
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("some/prefix/file.txt")));
    }

    @Test
    void rejectsTruncatedEntryData() throws IOException {
        final byte[] content = new byte[100];
        Arrays.fill(content, (byte) 'A');
        final byte[] fullEntry = entry("file.txt", content);
        // Keep only the header — stream ends before data can be read
        final byte[] headerOnly = Arrays.copyOf(fullEntry, BLOCK_SIZE);
        final var tarGz = gzipRaw(headerOnly);
        final var archive = writeTarGz(tarGz);
        assertThrows(IOException.class, () -> TarGzExtractor.extract(archive, tempDir));
    }

    @Test
    void handlesShortFinalBlock() throws IOException {
        final byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        final byte[] fullEntry = entry("file.txt", content);
        // Append fewer than BLOCK_SIZE zeros — next readNBytes returns a short array
        final byte[] tarBytes = new byte[fullEntry.length + 100];
        System.arraycopy(fullEntry, 0, tarBytes, 0, fullEntry.length);
        final var tarGz = gzipRaw(tarBytes);
        final var archive = writeTarGz(tarGz);
        TarGzExtractor.extract(archive, tempDir);
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("file.txt")));
    }

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

        recomputeChecksum(header);

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

    /** Gzips raw byte chunks without adding end-of-archive markers. */
    private static byte[] gzipRaw(final byte[]... chunks) throws IOException {
        final var gzBaos = new ByteArrayOutputStream();
        try (var gzos = new GZIPOutputStream(gzBaos)) {
            for (final byte[] chunk : chunks) {
                gzos.write(chunk);
            }
        }
        return gzBaos.toByteArray();
    }

    /** Recomputes the tar header checksum in place. */
    private static void recomputeChecksum(final byte[] header) {
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long checksum = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            checksum += (header[i] & 0xFF);
        }
        final String chkStr = String.format("%06o\0 ", checksum);
        System.arraycopy(chkStr.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 8);
    }

    /** Builds a raw 512-byte tar header with the given name, size, and type flag. */
    private static byte[] buildRawHeader(final String name, final long size, final byte typeFlag) {
        final byte[] header =
                Arrays.copyOf(buildEntry(name.isEmpty() ? "x" : name, new byte[0], typeFlag, ""), BLOCK_SIZE);
        writeOctal(header, 124, 12, size);
        recomputeChecksum(header);
        return header;
    }

    /** Creates a GNU long name (type 'L') entry followed by the actual file entry. */
    private static byte[] gnuLongNameEntry(final String longName, final byte[] content) {
        // The 'L' entry carries the long name (null-terminated) as its data
        final byte[] nameBytes = (longName + "\0").getBytes(StandardCharsets.US_ASCII);
        final byte[] lEntry = buildEntry("././@LongLink", nameBytes, (byte) 'L', "");
        // The actual file entry uses a truncated name in its header
        final String truncatedName = longName.substring(0, Math.min(longName.length(), 99));
        final byte[] fileEntry = buildEntry(truncatedName, content, (byte) '0', "");
        final byte[] result = new byte[lEntry.length + fileEntry.length];
        System.arraycopy(lEntry, 0, result, 0, lEntry.length);
        System.arraycopy(fileEntry, 0, result, lEntry.length, fileEntry.length);
        return result;
    }

    /** Creates a PAX header entry (type 'x', 'g', or 'X') with the given data. */
    private static byte[] paxEntry(final byte typeFlag, final byte[] paxData) {
        return buildEntry("PaxHeader", paxData, typeFlag, "");
    }

    /** Creates an entry using GNU binary size encoding (high bit set in size field). */
    private static byte[] entryWithBinarySize(final String name, final byte[] content) {
        final byte[] result = buildEntry(name, content, (byte) '0', "");
        // Overwrite size field with binary encoding
        Arrays.fill(result, 124, 136, (byte) 0);
        result[124] = (byte) 0x80; // high bit set
        long size = content.length;
        for (int i = 135; i >= 125; i--) {
            result[i] = (byte) (size & 0xFF);
            size >>= 8;
        }
        recomputeChecksum(result);
        return result;
    }

    /** Creates an entry with an all-zero size field (parsed as size 0). */
    private static byte[] entryWithEmptySize(final String name) {
        final byte[] result = buildEntry(name, new byte[0], (byte) '0', "");
        Arrays.fill(result, 124, 136, (byte) 0);
        recomputeChecksum(result);
        return result;
    }

    /** Creates an entry without the "ustar" magic — prefix is not used. */
    private static byte[] entryNoMagic(final String name, final byte[] content) {
        final byte[] result = buildEntry(name, content, (byte) '0', "");
        Arrays.fill(result, 257, 263, (byte) 0);
        recomputeChecksum(result);
        return result;
    }

    /** Creates a ustar entry with a prefix that gets concatenated with the name. */
    private static byte[] entryWithPrefix(final String prefix, final String name, final byte[] content) {
        final byte[] result = buildEntry(name, content, (byte) '0', "");
        final byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefixBytes, 0, result, 345, Math.min(prefixBytes.length, 155));
        recomputeChecksum(result);
        return result;
    }
}
