// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class RolloverFileOutputStreamTest {

    private static final String FILE_BASE_NAME = "testFile";
    private static final long MAX_FILE_SIZE = 1000; // 1 KB
    private static final boolean APPEND_TO_EXISTING = false;

    @TempDir(cleanup = CleanupMode.ALWAYS)
    private Path testFolder;

    @Test
    public void testDateSizeBaseRollingOverridingFirstExistingFile() throws IOException {
        // given: a RolloverFileOutputStream configure to roll by size with max 2 roll file.
        //   And 2 previously existing rolling files
        // when: Writing just enough data to trigger the rolling 1 time
        // then: the first previously existing file should be deleted and no newer roll file should exist

        // given
        createRolledFiles(testFolder.resolve(FILE_BASE_NAME), "0");
        createRolledFiles(testFolder.resolve(FILE_BASE_NAME), "1");

        try (OutputStream rolloverOutputStream = new RolloverFileOutputStream(
                testFolder.resolve(FILE_BASE_NAME), MAX_FILE_SIZE, APPEND_TO_EXISTING, 2)) {

            // when
            // Write enough data to fill one file
            writeDataToOutputStream(rolloverOutputStream, "Some data to fill one file.", MAX_FILE_SIZE);

            // then
            // Verify the existence of the rolled file
            Path rolledFilePath = testFolder.resolve(FILE_BASE_NAME + ".0");
            assertTrue(Files.exists(rolledFilePath), "no file %s found".formatted(rolledFilePath.toString()));
            assertEquals(MAX_FILE_SIZE, rolledFilePath.toFile().length());
            rolledFilePath = testFolder.resolve(FILE_BASE_NAME + ".1");
            assertTrue(Files.exists(rolledFilePath), "no file %s found".formatted(rolledFilePath.toString()));
            assertEquals(0, rolledFilePath.toFile().length());
            rolledFilePath = testFolder.resolve(FILE_BASE_NAME + ".2");
            assertFalse(
                    Files.exists(rolledFilePath),
                    "no file %s should have been found".formatted(rolledFilePath.toString()));
        }
    }

    @Test
    public void tesSizeBaseRollingOverriding() throws IOException {
        // given: a RolloverFileOutputStream configure to roll by size with max 1 roll file.
        // when: Writing just enough data to trigger the rolling 3 times
        // then: there should only exist the index 0. The base file should contain the latest under limit write

        // given
        try (OutputStream rolloverOutputStream = new RolloverFileOutputStream(
                testFolder.resolve(FILE_BASE_NAME), MAX_FILE_SIZE, APPEND_TO_EXISTING, 1)) {

            // when
            // Write enough data to fill one file
            writeDataToOutputStream(rolloverOutputStream, "Some data to fill one file.", MAX_FILE_SIZE);
            // Write more data to trigger rolling
            writeDataToOutputStream(rolloverOutputStream, "More data to trigger rolling.", MAX_FILE_SIZE);
            writeDataToOutputStream(rolloverOutputStream, "More data to trigger rolling.", MAX_FILE_SIZE);

            // then
            // Verify the existence of the rolled file
            Path rolledFilePath = testFolder.resolve(FILE_BASE_NAME + ".0");
            assertTrue(Files.exists(rolledFilePath), "no file %s found".formatted(rolledFilePath.toString()));
            assertFalse(Files.exists(testFolder.resolve(FILE_BASE_NAME + ".1")));
            assertEquals(MAX_FILE_SIZE, rolledFilePath.toFile().length());
        }
    }

    @Test
    void testWriteArrayFlushAndClose() throws IOException {
        // Creating an instance of RolloverFileOutputStream
        // Writing data to the stream
        // Flushing the stream
        // Closing the stream
        // Verifying that the log file exists and contains the written data
        // The written data should be in the file

        // given:
        Path logFilePath = testFolder.resolve(FILE_BASE_NAME);
        RolloverFileOutputStream outputStream = new RolloverFileOutputStream(logFilePath, MAX_FILE_SIZE, true, 1);
        // when:
        outputStream.write("Test data".getBytes(), 5, 4);
        outputStream.flush();
        outputStream.close();
        // then:
        assertTrue(Files.exists(logFilePath));
        assertEquals("data", Files.readString(logFilePath));
    }

    @Test
    void testWriteFlushAndClose() throws IOException {
        // Creating an instance of RolloverFileOutputStream
        // Writing data to the stream
        // Flushing the stream
        // Closing the stream
        // Verifying that the log file exists and contains the written data
        // The written data should be in the file

        // given:
        Path logFilePath = testFolder.resolve(FILE_BASE_NAME);
        RolloverFileOutputStream outputStream = new RolloverFileOutputStream(logFilePath, MAX_FILE_SIZE, true, 1);

        // when:
        String testData = "Test data";
        writeDataToOutputStream(outputStream, testData.getBytes());
        outputStream.flush();
        outputStream.close();

        // then:
        assertTrue(Files.exists(logFilePath));
        assertEquals(testData, Files.readString(logFilePath));
    }

    @Test
    void testWriteSingleFlushAndClose() throws IOException {
        // Creating an instance of RolloverFileOutputStream
        // Writing data to the stream
        // Flushing the stream
        // Closing the stream
        // Verifying that the log file exists and contains the written data
        // The written data should be in the file

        // given:
        Path logFilePath = testFolder.resolve(FILE_BASE_NAME);
        RolloverFileOutputStream outputStream = new RolloverFileOutputStream(logFilePath, MAX_FILE_SIZE, true, 1);
        // when:
        outputStream.write('c');
        outputStream.flush();
        outputStream.close();
        // then:
        assertTrue(Files.exists(logFilePath));
        assertEquals("c", Files.readString(logFilePath));
    }

    @Test
    void testAppendWriteSingleFlushAndClose() throws IOException {
        // Creating an instance of RolloverFileOutputStream
        // Writing data to the stream with previously existing content and append mode on
        // Flushing the stream
        // Closing the stream
        // Verifying that the log file exists and contains the written data
        // both the previous data and the new written data should be in the file

        // given:
        Path logFilePath = testFolder.resolve(FILE_BASE_NAME);
        String initialContent = "Initial Content String\n";
        Files.writeString(logFilePath, initialContent);

        RolloverFileOutputStream outputStream =
                new RolloverFileOutputStream(logFilePath, initialContent.length() * 2 + 1, true, 1);

        // when:
        writeDataToOutputStream(outputStream, initialContent.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        outputStream.close();

        // then:
        assertTrue(Files.exists(logFilePath));
        assertEquals(initialContent + initialContent, Files.readString(logFilePath));
    }

    @Test
    void testNoAppendWriteSingleFlushAndClose() throws IOException {
        // Creating an instance of RolloverFileOutputStream
        // Writing data to the stream with previously existing content and append mode off
        // Flushing the stream
        // Closing the stream
        // Verifying that the log file exists and contains the written data
        // only the new written data should be in the file

        // given
        Path logFilePath = testFolder.resolve(FILE_BASE_NAME);
        String initialContent = "Initial Content String\n";
        Files.writeString(logFilePath, initialContent);

        RolloverFileOutputStream outputStream =
                new RolloverFileOutputStream(logFilePath, initialContent.length() * 2 + 1, false, 1);

        // when
        writeDataToOutputStream(outputStream, initialContent.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        outputStream.close();

        // then
        assertTrue(Files.exists(logFilePath));
        assertEquals(initialContent, Files.readString(logFilePath));
    }

    @Test
    void testUnwritableFile() throws IOException {
        // Creating an non-writable path
        // When used to instantiate a  RolloverFileOutputStream
        // Should throw an IllegalStateException

        Path logFilePath = testFolder.resolve(FILE_BASE_NAME + "_tmp");
        Files.createDirectory(logFilePath);

        // Attempt to write to the file and assert that an IllegalStateException is thrown
        assertThrows(IllegalStateException.class, () -> {
            // Try to write something to the file
            try (RolloverFileOutputStream v = new RolloverFileOutputStream(logFilePath, MAX_FILE_SIZE, true, 1)) {}
        });
    }

    private static void writeDataToOutputStream(final OutputStream outputStream, final String data, long dataSize)
            throws IOException {
        byte[] bytes = new byte[(int) dataSize];
        for (int i = 0; i < dataSize; i++) {
            bytes[i] = (byte) data.charAt(i % data.length());
        }
        outputStream.write(bytes);
        outputStream.flush();
    }

    private static void writeDataToOutputStream(final OutputStream outputStream, final byte[] initialContent)
            throws IOException {
        outputStream.write(initialContent);
    }

    /**
     * Create rolled files with index suffixes
     */
    private static void createRolledFiles(Path logPath, String index) throws IOException {
        final String buffer = logPath.toString() + "." + index;
        Files.createFile(Paths.get(buffer));
    }
}
