// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.platform.health.filesystem.OSFileSystemCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OSFileSystemCheckTests {

    @TempDir
    private Path tmpDir;

    /**
     * Test basic invalid files and a valid file.
     */
    @Test
    @DisplayName("Basic Tests")
    void basicTest() {
        // Directory
        OSFileSystemCheck.Report report =
                assertDoesNotThrow(() -> OSFileSystemCheck.execute(tmpDir), "Check should not throw");
        assertEquals(OSFileSystemCheck.TestResultCode.FILE_IS_DIRECTORY, report.code(), "Unexpected report code");

        // File does not exist
        report = assertDoesNotThrow(
                () -> OSFileSystemCheck.execute(tmpDir.resolve("doesNotExist.txt")), "Check should not throw");
        assertEquals(OSFileSystemCheck.TestResultCode.FILE_DOES_NOT_EXIST, report.code(), "Unexpected report code");

        // File not readable
        final Path unreadableFile = createUnreadableFile();
        report = assertDoesNotThrow(() -> OSFileSystemCheck.execute(unreadableFile), "Check should not throw");
        assertEquals(OSFileSystemCheck.TestResultCode.FILE_NOT_READABLE, report.code(), "Unexpected report code");

        // Empty file
        final Path emptyFile = createEmptyFile();
        report = assertDoesNotThrow(() -> OSFileSystemCheck.execute(emptyFile), "Check should not throw");
        assertEquals(OSFileSystemCheck.TestResultCode.FILE_EMPTY, report.code(), "Unexpected report code");

        // File with data
        final String toWrite = "read me";
        final Path fileWithData = createFileWithData(toWrite);
        report = assertDoesNotThrow(() -> OSFileSystemCheck.execute(fileWithData), "Check should not throw");
        assertEquals(OSFileSystemCheck.TestResultCode.SUCCESS, report.code(), "Unexpected report code");
        assertNotNull(report.readNanos(), "Read nanos should not be null when the check succeeded");
        assertNotNull(report.data(), "Data read should not be null when the check succeeded");
        assertEquals(toWrite.getBytes()[0], report.data(), "Unexpected data read from file");
    }

    private Path createFileWithData(final String dataToWrite) {
        try {
            final Path fileWithData = tmpDir.resolve("fileWithData.txt");
            Files.createFile(fileWithData);
            Files.writeString(fileWithData, dataToWrite);
            return fileWithData;
        } catch (IOException e) {
            throw new RuntimeException("Unable to create file or write data to file", e);
        }
    }

    private Path createEmptyFile() {
        try {
            return Files.createFile(tmpDir.resolve("emptyFile.txt"));
        } catch (final IOException e) {
            throw new RuntimeException("Unable to create file", e);
        }
    }

    private Path createUnreadableFile() {
        try {
            final Path unreadableFile = tmpDir.resolve("unreadable.txt");
            Files.createFile(unreadableFile);
            PosixFileAttributeView attrs = Files.getFileAttributeView(unreadableFile, PosixFileAttributeView.class);
            attrs.setPermissions(Set.of(PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE));
            return unreadableFile;
        } catch (final IOException e) {
            throw new RuntimeException("Unable to create an unreadable file", e);
        }
    }
}
