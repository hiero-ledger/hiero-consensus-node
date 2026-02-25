// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.hiero.base.exceptions.NotImplementedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MerkleDBFileUtilsTest {
    private static File createTestFile(final String contents) throws IOException {
        final File testFile = createEmptyOutputFile();
        try (final FileOutputStream output = new FileOutputStream(testFile)) {
            output.write(contents.getBytes());
        }
        return testFile;
    }

    private static File createEmptyOutputFile() throws IOException {
        final File testFile = File.createTempFile("test-", "-tmp");
        testFile.deleteOnExit();
        return testFile;
    }

    private static final String EXAMPLE_STRING = "1234567890abcdefghijklmnopqrstuvwxyz........hello.world";
    private static final String FILLER_STRING = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    @DisplayName("completelyRead with small buffer")
    void completelyReadWhenSmallerBuffer() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[10];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
            assertEquals(10, bytesRead);
            assertArrayEquals("1234567890".getBytes(), contents);
            assertFalse(buffer.hasRemaining());
        }
    }

    @Test
    @DisplayName("read N bytes from file channel")
    void readNBytesFromFileChannel() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        FileChannel fileChannel = FileChannel.open(testFile.toPath(), StandardOpenOption.READ);
        int bytesToRead = 10;
        ByteBuffer result = MerkleDbFileUtils.readFromFileChannel(fileChannel, bytesToRead);
        assertEquals(bytesToRead, result.remaining());
        byte[] bytesFromFile = new byte[bytesToRead];
        result.get(bytesFromFile);

        assertEquals(EXAMPLE_STRING.substring(0, bytesToRead), new String(bytesFromFile));
    }

    @Test
    @DisplayName("completelyRead with larger buffer")
    void completelyReadWhenLargerBuffer() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length() + 100];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
            assertEquals(EXAMPLE_STRING.length(), bytesRead);
            assertTrue(buffer.hasRemaining());
            assertEquals(EXAMPLE_STRING.length(), buffer.position());
            assertEquals(100, buffer.remaining());
            final byte[] actualBytes = new byte[buffer.position()];
            buffer.rewind().get(actualBytes);
            assertArrayEquals(EXAMPLE_STRING.getBytes(), actualBytes);
        }
    }

    @Test
    @DisplayName("completelyRead with exact size buffer")
    void completelyReadWhenBufferSizeMatches() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
            assertEquals(EXAMPLE_STRING.length(), bytesRead);
            assertFalse(buffer.hasRemaining());
            assertEquals(EXAMPLE_STRING.length(), buffer.position());
            assertArrayEquals(EXAMPLE_STRING.getBytes(), contents);
        }
    }

    @Test
    @DisplayName("completelyRead with larger buffer and offset position")
    void completelyReadWhenLargerBufferWithOffsetPosition() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length() + 100];
        contents[0] = 'A';
        contents[1] = 'B';
        contents[2] = 'C';
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        buffer.position(3);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
            assertEquals(EXAMPLE_STRING.length(), bytesRead);
            assertTrue(buffer.hasRemaining());
            assertEquals(EXAMPLE_STRING.length() + 3, buffer.position());
            assertEquals(97, buffer.remaining());
            final byte[] actualBytes = new byte[buffer.position()];
            buffer.rewind().get(actualBytes);
            assertArrayEquals(("ABC1234567890abcdefghijklmnopqrstuvwxyz........hello.world").getBytes(), actualBytes);
        }
    }

    @Test
    @DisplayName("completelyRead with smaller buffer and offset position")
    void completelyReadWhenSmallerBufferWithOffsetPosition() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        contents[0] = 'A';
        contents[1] = 'B';
        contents[2] = 'C';
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        buffer.position(3);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
            assertEquals(EXAMPLE_STRING.length() - 3, bytesRead);
            assertFalse(buffer.hasRemaining());
            assertEquals(EXAMPLE_STRING.length(), buffer.position());
            assertArrayEquals("ABC1234567890abcdefghijklmnopqrstuvwxyz........hello.wo".getBytes(), contents);
        }
    }

    @Test
    @DisplayName("completelyRead with exact size buffer and interrupting channel")
    void completelyReadWhenFullBufferAndInterruptingChannel() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel flakeyChannel =
                new InterruptingFileChannel(FileChannel.open(testFile.toPath()), 10, 15, 20, 25, 30)) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(flakeyChannel, buffer);
            assertEquals(EXAMPLE_STRING.length(), bytesRead);
            assertFalse(buffer.hasRemaining());
            assertEquals(EXAMPLE_STRING.length(), buffer.position());
            assertArrayEquals(EXAMPLE_STRING.getBytes(), contents);
        }
    }

    @Test
    @DisplayName("completelyRead with smaller buffer and interrupting channel")
    void completelyReadWhenSmallerBufferAndInterruptingChannel() throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[15];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel flakeyChannel =
                new InterruptingFileChannel(FileChannel.open(testFile.toPath()), 10, 15, 20, 25, 30)) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(flakeyChannel, buffer);
            assertEquals(15, bytesRead);
            assertFalse(buffer.hasRemaining());
            assertEquals(15, buffer.position());
            assertArrayEquals("1234567890abcde".getBytes(), contents);
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1234567890", "3,4567890abc", "50,world"})
    @DisplayName("completelyRead with position parameter and smaller buffer")
    void completelyReadWithPositionWhenSmallerBuffer(final int startPosition, final String expectedAnswer)
            throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[10];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertArrayEquals(expectedAnswer.getBytes(), Arrays.copyOf(contents, bytesRead));
            assertEquals(0, fileChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world"
    })
    @DisplayName("completelyRead with position parameter and larger buffer")
    void completelyReadWithPositionWhenLargerBuffer(final int startPosition, final String expectedAnswer)
            throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length() + 100];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(expectedAnswer.length(), buffer.position());
            assertArrayEquals(expectedAnswer.getBytes(), Arrays.copyOf(contents, bytesRead));
            assertEquals(0, fileChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world"
    })
    @DisplayName("completelyRead with position parameter and exact size buffer")
    void completelyReadWithPositionWhenBufferSizeMatches(final int startPosition, final String expectedAnswer)
            throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(bytesRead, buffer.position());
            assertArrayEquals(expectedAnswer.getBytes(), Arrays.copyOf(contents, bytesRead));
            assertEquals(0, fileChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world",
    })
    @DisplayName("completelyRead with position parameter and larger buffer and initial offset in input buffer")
    void completelyReadWithPositionWhenLargerBufferWithOffsetPosition(
            final int startPosition, final String expectedAnswer) throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length() + 100];
        contents[0] = 'A';
        contents[1] = 'B';
        contents[2] = 'C';
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        buffer.position(3);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(expectedAnswer.length() + 3, buffer.position());
            assertArrayEquals(("ABC" + expectedAnswer).getBytes(), Arrays.copyOf(contents, bytesRead + 3));
            assertEquals(0, fileChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.wo",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world",
    })
    @DisplayName("completelyRead with position parameter and smaller buffer and initial offset in input buffer")
    void completelyReadWithPositionWhenSmallerBufferWithOffsetPosition(
            final int startPosition, final String expectedAnswer) throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        contents[0] = 'A';
        contents[1] = 'B';
        contents[2] = 'C';
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        buffer.position(3);
        try (final FileChannel fileChannel = FileChannel.open(testFile.toPath())) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(expectedAnswer.length() + 3, buffer.position());
            assertArrayEquals(("ABC" + expectedAnswer).getBytes(), Arrays.copyOf(contents, bytesRead + 3));
            assertEquals(0, fileChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world"
    })
    @DisplayName("completelyRead with position parameter and exact-size buffer and interrupting channel")
    void completelyReadWithPositionWhenFullBufferAndInterruptingChannel(
            final int startPosition, final String expectedAnswer) throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[EXAMPLE_STRING.length()];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel flakeyChannel =
                new InterruptingFileChannel(FileChannel.open(testFile.toPath()), 10, 15, 20, 25, 30)) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(flakeyChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(bytesRead, buffer.position());
            assertArrayEquals(expectedAnswer.getBytes(), Arrays.copyOf(contents, bytesRead));
            assertEquals(0, flakeyChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1234567890abcde", "3,4567890abcdefgh", "50,world"})
    @DisplayName("completelyRead with position parameter and smaller buffer and interrupting channel")
    void completelyReadWithPositionWhenSmallerBufferAndInterruptingChannel(
            final int startPosition, final String expectedAnswer) throws IOException {
        final File testFile = createTestFile(EXAMPLE_STRING);
        final byte[] contents = new byte[15];
        final ByteBuffer buffer = ByteBuffer.wrap(contents);
        try (final FileChannel flakeyChannel =
                new InterruptingFileChannel(FileChannel.open(testFile.toPath()), 10, 15, 20, 25, 30)) {
            final int bytesRead = MerkleDbFileUtils.completelyRead(flakeyChannel, buffer, startPosition);
            assertEquals(expectedAnswer.length(), bytesRead);
            assertEquals(expectedAnswer.length(), buffer.position());
            assertArrayEquals(expectedAnswer.getBytes(), Arrays.copyOf(contents, bytesRead));
            assertEquals(0, flakeyChannel.position());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world"
    })
    @DisplayName("completelyWrite with buffer at various initial offsets")
    void completelyWriteWithBufferAtDifferentOffsets(final int offset, final String expected) throws IOException {
        final File outputFile = createEmptyOutputFile();
        final ByteBuffer srcBuffer = ByteBuffer.wrap(EXAMPLE_STRING.getBytes()).position(offset);
        try (final FileOutputStream fos = new FileOutputStream(outputFile)) {
            final FileChannel fileChannel = fos.getChannel();
            final int bytesWritten = MerkleDbFileUtils.completelyWrite(fileChannel, srcBuffer);
            assertEquals(expected.length(), bytesWritten);
            assertEquals(expected.length(), fileChannel.position());
            assertEquals(offset + expected.length(), srcBuffer.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,4567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "50,world"
    })
    @DisplayName("completelyWrite with interrupting channel")
    void completelyWriteWithInterruptingChannel(final int offset, final String expected) throws IOException {
        final File outputFile = createEmptyOutputFile();
        final ByteBuffer srcBuffer = ByteBuffer.wrap(EXAMPLE_STRING.getBytes()).position(offset);

        try (final FileOutputStream fos = new FileOutputStream(outputFile)) {
            final InterruptingFileChannel fileChannel =
                    new InterruptingFileChannel(fos.getChannel(), 10, 15, 20, 25, 30);
            final int bytesWritten = MerkleDbFileUtils.completelyWrite(fileChannel, srcBuffer);
            assertEquals(expected.length(), bytesWritten);
            assertEquals(expected.length(), fileChannel.position());
            assertEquals(offset + expected.length(), srcBuffer.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,AAA1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "48,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1234567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".world",
    })
    @DisplayName("completelyWrite with position parameter")
    void completelyWriteWithPosition(final int startPos, final String expected) throws IOException {
        final File outputFile = createTestFile(FILLER_STRING);
        final ByteBuffer srcBuffer = ByteBuffer.wrap(EXAMPLE_STRING.getBytes());
        try (final FileChannel fileChannel =
                (FileChannel) Files.newByteChannel(outputFile.toPath(), StandardOpenOption.WRITE)) {
            final int bytesWritten = MerkleDbFileUtils.completelyWrite(fileChannel, srcBuffer, startPos);
            assertEquals(EXAMPLE_STRING.length(), bytesWritten);
            assertEquals(0, fileChannel.position());
            assertEquals(EXAMPLE_STRING.length(), srcBuffer.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    @ParameterizedTest
    @CsvSource({
        "0,1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "3,AAA1234567890abcdefghijklmnopqrstuvwxyz........hello.world",
        "48,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1234567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".world",
    })
    @DisplayName("completelyWrite with position parameter and interrupting file channel")
    void completelyWriteWithPositionAndInterruptingFileChannel(final int startPos, final String expected)
            throws IOException {
        final File outputFile = createTestFile(FILLER_STRING);
        final ByteBuffer srcBuffer = ByteBuffer.wrap(EXAMPLE_STRING.getBytes());
        try (final FileChannel fileChannel = new InterruptingFileChannel(
                (FileChannel) Files.newByteChannel(outputFile.toPath(), StandardOpenOption.WRITE),
                10,
                15,
                20,
                25,
                30)) {
            final int bytesWritten = MerkleDbFileUtils.completelyWrite(fileChannel, srcBuffer, startPos);
            assertEquals(EXAMPLE_STRING.length(), bytesWritten);
            assertEquals(0, fileChannel.position());
            assertEquals(EXAMPLE_STRING.length(), srcBuffer.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    @ParameterizedTest
    @CsvSource({
        "0,0,60,1234567890abcdefghijklmnopqrstuvwxyz........hello.world,55",
        "0,3,60,AAA1234567890abcdefghijklmnopqrstuvwxyz........hello.world,55",
        "0,48,60,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1234567890abcdefghijklmnopqrstuvwxyz......."
                + ".hello"
                + ".world,55",
        "3,0,60,4567890abcdefghijklmnopqrstuvwxyz........hello.worldAAA,52",
        "3,3,60,AAA4567890abcdefghijklmnopqrstuvwxyz........hello.world,52",
        "3,48,60,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".world,52",
        "3,0,50,4567890abcdefghijklmnopqrstuvwxyz........hello.worAAAAA,50",
        "3,3,50,AAA4567890abcdefghijklmnopqrstuvwxyz........hello.worAA,50",
        "3,48,50,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".wor,50",
    })
    @DisplayName("completelyTransferFrom with normal file channel")
    void completelyTransferFromWithNormalFileChannel(
            final int inChannelPosition,
            final int dstPosition,
            final int maxBytes,
            final String expected,
            final long expectedWritten)
            throws IOException {
        final File outputFile = createTestFile(FILLER_STRING);
        final File inputFile = createTestFile(EXAMPLE_STRING);

        try (final FileChannel outChannel =
                        (FileChannel) Files.newByteChannel(outputFile.toPath(), StandardOpenOption.WRITE);
                final FileChannel inChannel =
                        (FileChannel) Files.newByteChannel(inputFile.toPath(), StandardOpenOption.READ)) {
            inChannel.position(inChannelPosition);
            outChannel.position(3);
            final long bytesWritten =
                    MerkleDbFileUtils.completelyTransferFrom(outChannel, inChannel, dstPosition, maxBytes);
            assertEquals(expectedWritten, bytesWritten);
            assertEquals(expectedWritten + inChannelPosition, inChannel.position());
            assertEquals(3, outChannel.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    @ParameterizedTest
    @CsvSource({
        "0,0,60,1234567890abcdefghijklmnopqrstuvwxyz........hello.world,55",
        "0,3,60,AAA1234567890abcdefghijklmnopqrstuvwxyz........hello.world,55",
        "0,48,60,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1234567890abcdefghijklmnopqrstuvwxyz......."
                + ".hello"
                + ".world,55",
        "3,0,60,4567890abcdefghijklmnopqrstuvwxyz........hello.worldAAA,52",
        "3,3,60,AAA4567890abcdefghijklmnopqrstuvwxyz........hello.world,52",
        "3,48,60,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".world,52",
        "3,0,50,4567890abcdefghijklmnopqrstuvwxyz........hello.worAAAAA,50",
        "3,3,50,AAA4567890abcdefghijklmnopqrstuvwxyz........hello.worAA,50",
        "3,48,50,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4567890abcdefghijklmnopqrstuvwxyz........hello"
                + ".wor,50",
    })
    @DisplayName("completelyTransferFrom with interrupting file channel")
    void completelyTransferFromWithInterruptingFileChannel(
            final int inChannelPosition,
            final int dstPosition,
            final int maxBytes,
            final String expected,
            final long expectedWritten)
            throws IOException {
        final File outputFile = createTestFile(FILLER_STRING);
        final File inputFile = createTestFile(EXAMPLE_STRING);

        try (final FileChannel outChannel = new InterruptingFileChannel(
                        (FileChannel) Files.newByteChannel(outputFile.toPath(), StandardOpenOption.WRITE),
                        10,
                        15,
                        20,
                        25,
                        30);
                final FileChannel inChannel =
                        (FileChannel) Files.newByteChannel(inputFile.toPath(), StandardOpenOption.READ)) {
            inChannel.position(inChannelPosition);
            outChannel.position(3);
            final long bytesWritten =
                    MerkleDbFileUtils.completelyTransferFrom(outChannel, inChannel, dstPosition, maxBytes);
            assertEquals(expectedWritten, bytesWritten);
            assertEquals(expectedWritten + inChannelPosition, inChannel.position());
            assertEquals(3, outChannel.position());
        }
        assertArrayEquals(expected.getBytes(), Files.readAllBytes(outputFile.toPath()));
    }

    /**
     * A FileChannel that only reads/writes the maximum number of bytes specified in sequence.
     */
    private static class InterruptingFileChannel extends FileChannel {
        private final FileChannel delegate;
        private final int[] maxBytes;
        private int callCount = 0;

        public InterruptingFileChannel(final FileChannel delegate, final int... maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            final int maxBytesToRead = Math.min(dst.remaining(), maxBytes[callCount % maxBytes.length]);
            callCount++;
            final ByteBuffer intermediate = ByteBuffer.allocate(maxBytesToRead);
            final int bytesRead = delegate.read(intermediate);
            intermediate.rewind();
            dst.put(intermediate);
            return bytesRead;
        }

        @Override
        public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
            throw new NotImplementedException();
        }

        @Override
        public int write(final ByteBuffer dst) throws IOException {
            final int maxBytesToWrite = Math.min(dst.remaining(), maxBytes[callCount % maxBytes.length]);
            callCount++;
            final ByteBuffer intermediate = dst.slice(dst.position(), maxBytesToWrite);
            final int bytesWritten = delegate.write(intermediate);
            dst.position(dst.position() + bytesWritten);
            return bytesWritten;
        }

        @Override
        public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
            throw new NotImplementedException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(final long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(final long size) {
            throw new NotImplementedException();
        }

        @Override
        public void force(final boolean metaData) {
            throw new NotImplementedException();
        }

        @Override
        public long transferTo(final long position, final long count, final WritableByteChannel target) {
            throw new NotImplementedException();
        }

        @Override
        public long transferFrom(final ReadableByteChannel src, final long position, final long count)
                throws IOException {
            final FileChannel srcFileChannel = (FileChannel) src;
            final long bytesRemaining = srcFileChannel.size() - srcFileChannel.position();
            if (bytesRemaining <= 0) {
                return 0;
            }
            final int maxBytesToTransfer =
                    (int) Math.min(count, Math.min(bytesRemaining, maxBytes[callCount % maxBytes.length]));
            callCount++;
            final long startPos = srcFileChannel.position();
            final ByteBuffer intermediateBuffer = ByteBuffer.allocate(maxBytesToTransfer);
            srcFileChannel.read(intermediateBuffer);
            final int bytesWritten = delegate.write(intermediateBuffer.flip(), position);
            srcFileChannel.position(startPos + bytesWritten);
            return bytesWritten;
        }

        @Override
        public int read(final ByteBuffer dst, final long position) throws IOException {
            final int maxBytesToRead = Math.min(dst.remaining(), maxBytes[callCount % maxBytes.length]);
            callCount++;
            final ByteBuffer intermediate = ByteBuffer.allocate(maxBytesToRead);
            final int bytesRead = delegate.read(intermediate, position);
            dst.put(intermediate.flip());
            return bytesRead;
        }

        @Override
        public int write(final ByteBuffer src, final long position) throws IOException {
            final int maxBytesToWrite = Math.min(src.remaining(), maxBytes[callCount % maxBytes.length]);
            callCount++;
            final ByteBuffer intermediate = src.slice(src.position(), maxBytesToWrite);
            final int bytesWritten = delegate.write(intermediate, position);
            src.position(src.position() + bytesWritten);
            return bytesWritten;
        }

        @Override
        public MappedByteBuffer map(final MapMode mode, final long position, final long size) throws IOException {
            throw new NotImplementedException();
        }

        @Override
        public FileLock lock(final long position, final long size, final boolean shared) {
            throw new NotImplementedException();
        }

        @Override
        public FileLock tryLock(final long position, final long size, final boolean shared) {
            throw new NotImplementedException();
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
