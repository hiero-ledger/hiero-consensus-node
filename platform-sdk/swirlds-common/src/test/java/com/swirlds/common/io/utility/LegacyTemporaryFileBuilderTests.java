// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectory;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryDirectory;
import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryFile;
import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.getTemporaryFileLocation;
import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.overrideTemporaryFileLocation;
import static java.nio.file.Files.exists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TemporaryFileBuilder Tests")
public class LegacyTemporaryFileBuilderTests {

    private static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();

    @Test
    @DisplayName("buildTemporaryDirectory() Test")
    @SuppressWarnings("ConstantConditions")
    void buildTemporaryDirectoryTest() throws IOException {
        final Path tmp = buildTemporaryDirectory(CONFIGURATION);
        assertTrue(exists(tmp), "directory should exist");
        assertTrue(tmp.toFile().canRead(), "invalid permissions, should be able to read");
        assertTrue(tmp.toFile().canWrite(), "invalid permissions, should be able to write");
        assertTrue(Files.isDirectory(tmp), "invalid file type");
        assertEquals(0, tmp.toFile().listFiles().length, "should have no children");
        assertTrue(tmp.toFile().delete(), "unable to delete temporary directory");
    }

    @Test
    @DisplayName("Unique Files Test")
    void uniqueFilesTest() throws IOException {
        final Set<Path> files = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                assertTrue(files.add(buildTemporaryFile(CONFIGURATION)), "file should not yet exist");
            } else {
                assertTrue(files.add(buildTemporaryDirectory(CONFIGURATION)), "file should not yet exist");
            }
        }

        // Cleanup
        deleteDirectory(getTemporaryFileLocation(CONFIGURATION));
    }

    @Test
    @DisplayName("Postfix Test")
    void postfixTest() throws IOException {
        final Path file = buildTemporaryFile("foo", CONFIGURATION);
        final Path directory = buildTemporaryFile("bar", CONFIGURATION);

        final String fileName = file.getFileName().toString();
        final String[] fileNameElements = fileName.split("-");
        assertEquals(2, fileNameElements.length, "invalid file name format");
        assertEquals("foo", fileNameElements[1], "invalid postfix");

        final String directoryName = directory.getFileName().toString();
        final String[] directoryNameElements = directoryName.split("-");
        assertEquals(2, directoryNameElements.length, "invalid directory name format");
        assertEquals("bar", directoryNameElements[1], "invalid postfix");

        // Cleanup
        deleteDirectory(getTemporaryFileLocation(CONFIGURATION));
    }

    @Test
    @DisplayName("Auto Cleanup Test")
    void autoCleanupTest() throws IOException {
        final List<Path> files = new LinkedList<>();

        for (int i = 0; i < 100; i++) {
            assertTrue(files.add(buildTemporaryDirectory(CONFIGURATION)), "file should not yet exist");
        }

        for (final Path file : files) {
            assertTrue(exists(file), "file should still exist");
        }

        // This should cause all files to be deleted
        overrideTemporaryFileLocation(getTemporaryFileLocation(CONFIGURATION));

        for (final Path file : files) {
            assertFalse(exists(file), "file should have been deleted");
        }
    }

    @Test
    @DisplayName("Set Temporary File Directory Test")
    void setTemporaryFileDirectoryTest() throws IOException {

        final Path originalTemporaryFileLocation = getTemporaryFileLocation(CONFIGURATION);

        overrideTemporaryFileLocation(getAbsolutePath("foobar"));
        final Path file = buildTemporaryFile(CONFIGURATION);
        assertEquals("foobar", file.getParent().getFileName().toString(), "invalid location");
        deleteDirectory(getTemporaryFileLocation(CONFIGURATION));

        // Reset location for other tests
        overrideTemporaryFileLocation(originalTemporaryFileLocation);
    }
}
