// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.stream.internal.TimestampStreamFileWriter;
import com.swirlds.common.test.fixtures.stream.ObjectForTestStream;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimestampStreamFileWriter Test")
class TimestampStreamFileWriterTest {

    /**
     * Verify that a sequence of objects added to the stream create files at the expected times.
     *
     * @param windowSizeMs
     * 		the window size
     * @param startAtCompleteWindow
     * 		true if only complete files should be written
     * @param objectsToAdd
     * 		a sequence of objects to add
     */
    private static void verifyNewFileSequence(
            final int windowSizeMs, final boolean startAtCompleteWindow, final NextStreamObject... objectsToAdd) {

        final TimestampStreamFileWriter<ObjectForTestStream> stream =
                new TimestampStreamFileWriter<>(null, windowSizeMs, null, startAtCompleteWindow, null);

        for (final NextStreamObject nextStreamObject : objectsToAdd) {
            assertEquals(
                    nextStreamObject.shouldStartNewFile(),
                    stream.shouldStartNewFile(nextStreamObject.object()),
                    nextStreamObject.errorMessage());
        }
    }

    @Test
    @DisplayName("shouldStartNewFile() Complete Window Test")
    void shouldStartNewFileCompleteWindowTest() {

        final int windowSizeMs = 500;
        final Instant firstPeriodStart = Instant.ofEpochSecond(1000);

        int id = 0;

        verifyNewFileSequence(
                windowSizeMs,
                true,
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart),
                        false,
                        "the first object should not be written"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs / 2)),
                        false,
                        "the second object is in the same period as the first object"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs)),
                        true,
                        "the third object is in the next period, should start writing"),
                new NextStreamObject(
                        new ObjectForTestStream(id, firstPeriodStart.plusMillis(2 * windowSizeMs)),
                        true,
                        "the fourth object is in the next period, should write to a new file"));
    }

    @Test
    @DisplayName("shouldStartNewFile() Partial Window Test")
    void shouldStartNewFilePartialWindowTest() {

        final int windowSizeMs = 500;
        final Instant firstPeriodStart = Instant.ofEpochSecond(1000);

        int id = 0;

        verifyNewFileSequence(
                windowSizeMs,
                false,
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart),
                        true,
                        "the first object should write a new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs / 2)),
                        false,
                        "the second object is in the same period as the first object, "
                                + "should not write a new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs)),
                        true,
                        "the third object in the next period should be written to a new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id, firstPeriodStart.plusMillis(2 * windowSizeMs)),
                        true,
                        "the fourth object is in the next period, should write a new file"));
    }

    @Test
    @DisplayName("shouldStartNewFile() Aligned Complete Window Test")
    void shouldStartNewFileAlignedCompleteWindowTest() {

        final int windowSizeMs = 500;
        final Instant firstPeriodStart = Instant.ofEpochSecond(1000);

        int id = 0;
        long alignment = 0;

        verifyNewFileSequence(
                windowSizeMs,
                true,
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart, alignment),
                        false,
                        "not enough info to decide if this belongs at the beginning of a file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs / 2), ++alignment),
                        false,
                        "same period but different alignment, should not start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs), alignment),
                        false,
                        "new period but same alignment, should not start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs + 1), ++alignment),
                        true,
                        "first non-repeating alignment in this period, should start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs + 2), ++alignment),
                        false,
                        "new alignment but same period, should not start new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(2 * windowSizeMs), alignment),
                        false,
                        "new period but same alignment, should not start new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id, firstPeriodStart.plusMillis(3 * windowSizeMs), ++alignment),
                        true,
                        "new period and alignment, should start new file"));
    }

    @Test
    @DisplayName("shouldStartNewFile() Aligned Partial Window Test")
    void shouldStartNewFileAlignedPartialWindowTest() {

        final int windowSizeMs = 500;
        final Instant firstPeriodStart = Instant.ofEpochSecond(1000);

        int id = 0;
        long alignment = 0;

        verifyNewFileSequence(
                windowSizeMs,
                true,
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart, alignment),
                        false,
                        "first object should be placed into a file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs / 2), ++alignment),
                        false,
                        "same period but different alignment, should not start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs), alignment),
                        false,
                        "new period but same alignment, should not start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs + 1), ++alignment),
                        true,
                        "first non-repeating alignment in this period, should start first file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(windowSizeMs + 2), ++alignment),
                        false,
                        "new alignment but same period, should not start new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id++, firstPeriodStart.plusMillis(2 * windowSizeMs), alignment),
                        false,
                        "new period but same alignment, should not start new file"),
                new NextStreamObject(
                        new ObjectForTestStream(id, firstPeriodStart.plusMillis(3 * windowSizeMs), ++alignment),
                        true,
                        "new period and alignment, should start new file"));
    }

    /**
     * @param object
     * 		an object that will be added to the stream
     * @param shouldStartNewFile
     * 		if this object should cause the creation of a new file
     * @param errorMessage
     * 		a message to be printed if file creation does not follow expectations
     */
    private record NextStreamObject(ObjectForTestStream object, boolean shouldStartNewFile, String errorMessage) {}
}
