// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.PathsConfig_;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MarkerFileWriterTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    private Path testDirectoryPath;

    @Test
    void testWriteMarkerFile() {
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue(PathsConfig_.MARKER_FILES_DIR, testDirectoryPath.toString())
                        .withValue(PathsConfig_.WRITE_PLATFORM_MARKER_FILES, true)
                        .getOrCreateConfig())
                .build();

        // create a new MarkerFileWriter
        final MarkerFileWriter markerFileWriter = new MarkerFileWriter(platformContext);
        assertNotNull(markerFileWriter);

        // check that the marker file directory was created
        assertTrue(Files.exists(testDirectoryPath));

        // write a marker file
        markerFileWriter.writeMarkerFile("testMarkerFile");
        final Path markerFile = testDirectoryPath.resolve("testMarkerFile");
        assertTrue(Files.exists(markerFile));

        // write a different marker file
        markerFileWriter.writeMarkerFile("testMarkerFile2");
        final Path markerFile2 = testDirectoryPath.resolve("testMarkerFile2");
        assertTrue(Files.exists(markerFile2));

        // verify original marker file exists
        assertTrue(Files.exists(markerFile));

        // get timestamp of original marker file
        final long originalMarkerFileTimestamp = markerFile.toFile().lastModified();

        // write first marker file a second time
        markerFileWriter.writeMarkerFile("testMarkerFile");

        // assert original marker file timestamp has not changed
        assertEquals(originalMarkerFileTimestamp, markerFile.toFile().lastModified());

        // verify there are only two marker files in the directory
        assertEquals(2, testDirectoryPath.toFile().listFiles().length);
    }

    @Test
    void testWriteMarkerFileNoDirectory() {
        final PlatformContext platformContextEmptyString = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("paths.markerFilesDir", "")
                        .getOrCreateConfig())
                .build();

        // create a new MarkerFileWriter
        MarkerFileWriter markerFileWriter = new MarkerFileWriter(platformContextEmptyString);
        assertNull(markerFileWriter.getMarkerFileDirectory());

        final PlatformContext platformContextDevNull = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("paths.markerFilesDir", "/dev/null")
                        .getOrCreateConfig())
                .build();

        // create a new MarkerFileWriter
        markerFileWriter = new MarkerFileWriter(platformContextDevNull);
        assertNull(markerFileWriter.getMarkerFileDirectory());
    }
}
