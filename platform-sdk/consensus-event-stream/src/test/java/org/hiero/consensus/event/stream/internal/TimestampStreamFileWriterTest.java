// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.internal;

import static java.util.stream.Collectors.toSet;
import static org.hiero.consensus.event.stream.LinkedObjectStreamUtilities.computeEntireHash;
import static org.hiero.consensus.event.stream.LinkedObjectStreamUtilities.generateSigFilePath;
import static org.hiero.consensus.event.stream.LinkedObjectStreamUtilities.generateStreamFileNameFromInstant;
import static org.hiero.consensus.event.stream.internal.StreamValidationResult.OK;
import static org.hiero.consensus.event.stream.test.fixtures.TestStreamType.TEST_STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.event.stream.LinkedObjectStream;
import org.hiero.consensus.event.stream.RunningHashCalculatorForStream;
import org.hiero.consensus.event.stream.test.fixtures.ObjectForTestStream;
import org.hiero.consensus.event.stream.test.fixtures.StreamFileSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("restartAtCompleteWindow() discards the partial file and resumes at the next complete window")
    void restartAtCompleteWindowDiscardsPartialFile(@TempDir final Path directory)
            throws IOException, NoSuchAlgorithmException, ConstructableRegistryException {
        ConstructableRegistration.registerCoreConstructables();

        final long windowSizeMs = 1000;
        final Instant start = Instant.ofEpochSecond(1000);
        final StreamFileSigner signer = new StreamFileSigner();
        final TimestampStreamFileWriter<ObjectForTestStream> writer =
                new TimestampStreamFileWriter<>(directory.toString(), windowSizeMs, signer, false, TEST_STREAM);
        final LinkedObjectStream<ObjectForTestStream> stream =
                new HashCalculatorForStream<>(new RunningHashCalculatorForStream<>(writer));
        stream.setRunningHash(new Hash(new byte[DigestType.SHA_384.digestLength()], DigestType.SHA_384));

        int id = 0;
        // the first window is complete, the second window is still being written
        stream.addObject(new ObjectForTestStream(id++, start));
        stream.addObject(new ObjectForTestStream(id++, start.plusMillis(1000)));
        stream.addObject(new ObjectForTestStream(id++, start.plusMillis(1500)));

        writer.restartAtCompleteWindow();

        // the first object after the restart is in the middle of a window
        stream.addObject(new ObjectForTestStream(id++, start.plusMillis(5500)));
        // the next window is complete once the object of the following window arrives
        stream.addObject(new ObjectForTestStream(id++, start.plusMillis(6000)));
        stream.addObject(new ObjectForTestStream(id, start.plusMillis(7000)));

        final String firstFile = generateStreamFileNameFromInstant(start, TEST_STREAM);
        final String restartedFile = generateStreamFileNameFromInstant(start.plusMillis(6000), TEST_STREAM);
        final String currentFile = generateStreamFileNameFromInstant(start.plusMillis(7000), TEST_STREAM);
        try (final Stream<Path> files = Files.list(directory)) {
            assertEquals(
                    Set.of(firstFile, firstFile + "_sig", restartedFile, restartedFile + "_sig", currentFile),
                    files.map(file -> file.getFileName().toString()).collect(toSet()),
                    "the partial window must be deleted and writing must resume at the next complete window");
        }

        final File restartedStreamFile = directory.resolve(restartedFile).toFile();
        assertEquals(
                OK,
                LinkedObjectStreamValidateUtils.validateSignature(
                        computeEntireHash(restartedStreamFile),
                        new File(generateSigFilePath(restartedStreamFile)),
                        signer.getPublicKey(),
                        TEST_STREAM),
                "the deleted file must not affect the signature of the next file");

        writer.close();
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
