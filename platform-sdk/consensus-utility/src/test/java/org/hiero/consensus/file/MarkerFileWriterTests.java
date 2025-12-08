// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
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
        // create a new MarkerFileWriter
        final MarkerFileWriter markerFileWriter = new MarkerFileWriter(Time.getCurrent(), true, testDirectoryPath);
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
        // create a new MarkerFileWriter
        MarkerFileWriter markerFileWriter = new MarkerFileWriter(Time.getCurrent(), true, Path.of(""));
        assertNull(markerFileWriter.getMarkerFileDirectory());

        // create a new MarkerFileWriter
        markerFileWriter = new MarkerFileWriter(Time.getCurrent(), true, Path.of("/dev/null"));
        assertNull(markerFileWriter.getMarkerFileDirectory());
    }
}
