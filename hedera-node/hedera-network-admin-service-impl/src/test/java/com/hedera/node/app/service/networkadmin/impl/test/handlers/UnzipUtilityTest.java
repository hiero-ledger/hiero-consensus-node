// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.hedera.node.app.service.networkadmin.impl.handlers.UnzipUtility;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({LogCaptureExtension.class, MockitoExtension.class})
class UnzipUtilityTest {

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private UnzipUtility subject;

    // These @TempDir folders and the files created in them will be deleted after
    // tests are run, even in the event of failures or exceptions.
    @TempDir
    private Path unzipOutputDir; // unzip test zips to this directory

    @TempDir
    private Path zipSourceDir; // contains test zips

    private File testZipWithOneFile;
    private File testZipWithSubdirectory;
    private File testZipWithDirectoryOnly;
    private File testZipEmpty;
    private final String FILENAME_1 = "fileToZip.txt";
    private final String FILENAME_2 = "subdirectory/subdirectoryFileToZip.txt";
    private final String DIRECTORY_ONLY = "emptyDir/";

    @BeforeEach
    void setup() throws IOException {
        zipSourceDir = Files.createTempDirectory("zipSourceDir");

        // set up test zip with one file in it
        testZipWithOneFile = new File(zipSourceDir + "/testZipWithOneFile.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(testZipWithOneFile))) {
            ZipEntry e = new ZipEntry(FILENAME_1);
            out.putNextEntry(e);

            String fileContent = "Time flies like an arrow but fruit flies like a banana";
            byte[] data = fileContent.getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }

        // set up test zip with a file and a subdirectory in it
        testZipWithSubdirectory = new File(zipSourceDir + "/testZipWithSubdirectory.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(testZipWithSubdirectory))) {
            ZipEntry e1 = new ZipEntry(FILENAME_1);
            out.putNextEntry(e1);
            byte[] data = "Time flies like an arrow but fruit flies like a banana".getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();

            ZipEntry e2 = new ZipEntry(FILENAME_2);
            out.putNextEntry(e2);
            data = "If you aren't fired with enthusiasm, you will be fired, with enthusiasm".getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }

        // set up test zip with directory only
        testZipWithDirectoryOnly = new File(zipSourceDir + "/testZipWithDirectoryOnly.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(testZipWithDirectoryOnly))) {
            ZipEntry e = new ZipEntry(DIRECTORY_ONLY);
            out.putNextEntry(e);
            out.closeEntry();
        }

        // set up empty zip file
        testZipEmpty = new File(zipSourceDir + "/testZipEmpty.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(testZipEmpty))) {
            // Create empty zip
        }
    }

    @Test
    void unzipOneFileSucceedsAndLogs() throws IOException {
        final var data = Files.readAllBytes(testZipWithOneFile.toPath());
        assertThatCode(() -> UnzipUtility.unzip(data, unzipOutputDir)).doesNotThrowAnyException();
        final Path path = unzipOutputDir.resolve(FILENAME_1);
        assert (path.toFile().exists());
        assert (logCaptor.infoLogs().contains("- Extracted update file " + path));
    }

    @Test
    void unzipWithSubDirectorySucceedsAndLogs() throws IOException {
        final var data = Files.readAllBytes(testZipWithSubdirectory.toPath());
        assertThatCode(() -> UnzipUtility.unzip(data, unzipOutputDir)).doesNotThrowAnyException();
        final Path path = unzipOutputDir.resolve(FILENAME_1);
        assert (path.toFile().exists());
        assert (logCaptor.infoLogs().contains("- Extracted update file " + path));

        final Path path2 = unzipOutputDir.resolve(FILENAME_2);
        assert (path2.toFile().exists());
        assert (logCaptor.infoLogs().contains("- Extracted update file " + path2));
    }

    @Test
    void unzipDirectoryOnlySucceedsAndLogs() throws IOException {
        final var data = Files.readAllBytes(testZipWithDirectoryOnly.toPath());
        assertThatCode(() -> UnzipUtility.unzip(data, unzipOutputDir)).doesNotThrowAnyException();
        final Path dirPath = unzipOutputDir.resolve(DIRECTORY_ONLY);
        assert (dirPath.toFile().exists() && dirPath.toFile().isDirectory());
        assert (logCaptor.infoLogs().contains("- Created assets sub-directory " + dirPath.toFile()));
    }

    @Test
    void failsWhenEmptyZip() throws IOException {
        final var data = Files.readAllBytes(testZipEmpty.toPath());
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(data, unzipOutputDir))
                .withMessage("No zip entry found in bytes");
    }

    @Test
    void failsWhenArchiveIsInvalidZip() {
        final byte[] data = new byte[] {'a', 'b', 'c'};
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> UnzipUtility.unzip(data, unzipOutputDir));
    }

    @Test
    @DisplayName("Unzip Fails when Unable to Create the Parent Directories")
    void failsWhenUnableToCreateParentDirectory() throws IOException {
        final var data = Files.readAllBytes(testZipWithOneFile.toPath());
        // Mock the destination directory
        Path dstDirMock = Paths.get("/mocked/dst/dir");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(data, dstDirMock))
                .withMessage("Unable to create the parent directories for the file: " + dstDirMock + "/fileToZip.txt");
    }

    @Test
    @DisplayName("Unzip Fails when trying to extract file outside destination directory (Zip Slip attack)")
    void failsWhenZipSlipAttackAttempted() throws IOException {
        // Create a malicious zip with entry pointing outside destination
        byte[] maliciousZip = createMaliciousZip();
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(maliciousZip, unzipOutputDir))
                // May fail fast on traversal validation, or later on canonical-path Zip Slip protection.
                .withMessageContaining("Zip entry name contains path traversal");
    }

    @Test
    @DisplayName("Unzip Fails when unable to create subdirectory")
    void failsWhenUnableToCreateSubdirectory() throws IOException {
        // Create zip with subdirectory entry
        byte[] zipWithDir = createZipWithDirectory();
        Path invalidDstDir = Paths.get("/invalid/path/that/cannot/be/created");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zipWithDir, invalidDstDir))
                .withMessageContaining("Unable to create the parent directories for the file");
    }

    @Test
    void testNullParameters() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> UnzipUtility.unzip(null, unzipOutputDir));

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> UnzipUtility.unzip(new byte[0], null));
    }

    @Test
    @DisplayName("Unzip fails when zip entry name contains control characters")
    void failsWhenEntryNameHasControlCharacters() throws IOException {
        final byte[] zip = createZipWithSingleEntryName("bad\nname.txt", "content".getBytes());
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zip, unzipOutputDir))
                .withMessageContaining("control character");
    }

    @Test
    @DisplayName("Unzip fails when zip entry name contains null bytes")
    void failsWhenEntryNameHasNullBytes() throws IOException {
        final byte[] zip = createZipWithSingleEntryName("bad\u0000name.txt", "content".getBytes());
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zip, unzipOutputDir))
                .withMessageContaining("control character");
    }

    @Test
    @DisplayName("Unzip fails when zip entry name is too long")
    void failsWhenEntryNameIsTooLong() throws IOException {
        final byte[] zip = createZipWithSingleEntryName("a".repeat(4097), "content".getBytes());
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zip, unzipOutputDir))
                .withMessageContaining("name is too long");
    }

    @Test
    @DisplayName("Unzip fails when zip entry is nested too deeply")
    void failsWhenEntryDepthTooDeep() throws IOException {
        final byte[] zip = createZipWithSingleEntryName(buildDeepPath(21) + "/file.txt", "content".getBytes());
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zip, unzipOutputDir))
                .withMessageContaining("nested too deeply");
    }

    @Test
    @DisplayName("Unzip fails when archive contains too many entries")
    void failsWhenTooManyEntries() throws IOException {
        final byte[] zip = createZipWithNEntries(10_001);
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> UnzipUtility.unzip(zip, unzipOutputDir))
                .withMessageContaining("too many entries");
    }

    @Test
    @DisplayName("extractSingleFileWithLimits fails when entry exceeds max bytes and cleans up output file")
    void extractSingleFileFailsWhenEntryExceedsMaxBytesAndCleansUp() throws Exception {
        final var content = "0123456789".getBytes();
        final var zip = createZipWithSingleEntryName("big.txt", content);
        final var out = unzipOutputDir.resolve("big.txt");

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> extractWithLimits(zip, out, 5L, Long.MAX_VALUE))
                .withMessageContaining("Zip entry exceeds max allowed size (5 bytes)");

        assertThat(out).doesNotExist();
    }

    @Test
    @DisplayName(
            "extractSingleFileWithLimits fails when remaining total bytes would be exceeded and cleans up output file")
    void extractSingleFileFailsWhenRemainingTotalBytesExceededAndCleansUp() throws Exception {
        final var content = "0123456789".getBytes();
        final var zip = createZipWithSingleEntryName("big-total.txt", content);
        final var out = unzipOutputDir.resolve("big-total.txt");

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> extractWithLimits(zip, out, 100L, 5L))
                .withMessageContaining("Zip archive exceeds max allowed extracted size");

        assertThat(out).doesNotExist();
    }

    @Test
    @DisplayName("extractSingleFileWithLimits logs warn when cleanup cannot delete partial output")
    void extractSingleFileLogsWarnWhenCleanupCannotDelete() throws Exception {
        final var zip = createZipWithSingleEntryName("entry.txt", "content".getBytes());
        final var outDir = unzipOutputDir.resolve("not-a-file");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("child.txt"), "x");

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> extractWithLimits(zip, outDir, 100L, Long.MAX_VALUE));
        assertThat(logCaptor.warnLogs())
                .anyMatch(l -> l.contains("Unable to delete partially extracted file " + outDir));
    }

    private byte[] createMaliciousZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Create entry with path traversal
            ZipEntry entry = new ZipEntry("../../../malicious.txt");
            zos.putNextEntry(entry);
            zos.write("malicious content".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createZipWithDirectory() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Create directory entry
            ZipEntry dirEntry = new ZipEntry("testdir/");
            zos.putNextEntry(dirEntry);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createZipWithSingleEntryName(final String entryName, final byte[] content) throws IOException {
        final var baos = new ByteArrayOutputStream();
        try (final var zos = new ZipOutputStream(baos)) {
            final var entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createZipWithNEntries(final int n) throws IOException {
        final var baos = new ByteArrayOutputStream();
        try (final var zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < n; i++) {
                final var entry = new ZipEntry("file-" + i + ".txt");
                zos.putNextEntry(entry);
                zos.write("x".getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static String buildDeepPath(final int depth) {
        final var sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append('/');
            sb.append('a');
        }
        return sb.toString();
    }

    private static long extractWithLimits(
            final byte[] zipBytes, final Path outputPath, final long maxEntryBytes, final long remainingTotalBytes)
            throws IOException {
        try (final var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            final var entry = zis.getNextEntry();
            if (entry == null) {
                throw new IOException("No zip entry found in bytes");
            }
            final var method = UnzipUtility.class.getDeclaredMethod(
                    "extractSingleFileWithLimits", ZipInputStream.class, Path.class, long.class, long.class);
            method.setAccessible(true);
            return (long) method.invoke(null, zis, outputPath, maxEntryBytes, remainingTotalBytes);
        } catch (final InvocationTargetException e) {
            final var cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
