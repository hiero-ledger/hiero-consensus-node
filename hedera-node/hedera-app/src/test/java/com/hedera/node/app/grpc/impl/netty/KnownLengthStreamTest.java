// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class KnownLengthStreamTest {
    @Test
    @DisplayName("KnownLengthStream cannot be created with a null buffer")
    void nullBufferThrows() {
        //noinspection resource
        assertThrows(NullPointerException.class, () -> new KnownLengthStream(null));
    }

    private static Stream<Arguments> provideBuffers() {
        return Stream.of(Arguments.of(0, 0), Arguments.of(100, 0), Arguments.of(100, 80), Arguments.of(100, 100));
    }

    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("The stream agrees with the buffer on how many bytes are available")
    void available(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);
        try (final var stream = new KnownLengthStream(buf)) {
            assertEquals((int) buf.remaining(), stream.available());
        }
    }

    @ParameterizedTest(name = "Skipping {0} bytes")
    @ValueSource(ints = {-1, 0})
    @DisplayName("The stream does nothing with skip 0 or -1")
    void skipNothing(int num) throws IOException {
        final var arr = TestUtils.randomBytes(100);
        final var buf = BufferedData.wrap(arr);
        buf.skip(3); // random value
        try (final var stream = new KnownLengthStream(buf)) {
            assertEquals(0, stream.skip(num));
        }
    }

    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("The stream can skip bytes")
    void skip(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);
        try (final var stream = new KnownLengthStream(buf)) {
            final int bytesToRead = (int) buf.remaining();
            final int step = bytesToRead / 3;
            for (int i = 0; i < bytesToRead; i += step) {
                int remaining = stream.available();
                int numSkipped = (int) stream.skip(step);
                assertEquals(Math.min(remaining, step), numSkipped);
            }

            assertEquals(0, stream.skip(1));
        }
    }

    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("The stream can skip N bytes")
    void skipN(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);
        try (final var stream = new KnownLengthStream(buf)) {
            final int bytesToRead = (int) buf.remaining();
            final int step = bytesToRead / 3;
            for (int i = 0; i < bytesToRead; i += step) {
                int remaining = stream.available();
                if (remaining >= step) {
                    stream.skipNBytes(step);
                } else {
                    assertThrows(EOFException.class, () -> stream.skipNBytes(step));
                }
            }

            assertThrows(EOFException.class, () -> stream.skipNBytes(1));
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "read" method and eventually get -1.
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with read()")
    void read(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);

        try (final var stream = new KnownLengthStream(buf)) {
            final var numBytesToRead = (int) buf.remaining();
            for (int i = 0; i < numBytesToRead; i++) {
                assertEquals(Byte.toUnsignedInt(arr[i + position]), stream.read());
                assertEquals(numBytesToRead - i - 1, stream.available());
            }

            assertEquals(-1, stream.read());
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "read" method with a byte array.
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with read(byte[])")
    void readBytes(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);

        try (final var stream = new KnownLengthStream(buf)) {
            // Read the bytes from the stream in chunks, such that we get a chunk
            // that completely fits within the num bytes to read, and a chunk
            // that read the rest of the bytes but has some space remaining,
            // and then read when there are no bytes left to make sure we get
            // a -1
            int numBytesToRead = (int) buf.remaining();
            final int chunkSize = (int) (numBytesToRead * .75);
            int numBytesRead;
            for (int i = 0; i < numBytesToRead; i += numBytesRead) {
                assert chunkSize > 0;
                final byte[] chunk = new byte[chunkSize];
                numBytesRead = stream.read(chunk);

                assertTrue(numBytesRead > 0);
                assertTrue(Arrays.equals(arr, position + i, position + i + numBytesRead, chunk, 0, numBytesRead));
            }

            assertEquals(-1, stream.read(new byte[capacity]));
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "read" method with a byte array, offset, and len.
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with read(byte[], off, len)")
    void readBytesOffLen(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);

        try (final var stream = new KnownLengthStream(buf)) {
            // Read the bytes from the stream in chunks, such that we get a chunk
            // that completely fits within the num bytes to read, and a chunk
            // that read the rest of the bytes but has some space remaining,
            // and then read when there are no bytes left to make sure we get
            // a -1
            int numBytesToRead = (int) buf.remaining();
            final int chunkSize = (int) (numBytesToRead * .75);
            int numBytesRead;
            for (int i = 0; i < numBytesToRead; i += numBytesRead) {
                assert chunkSize > 0;
                final byte[] chunk = new byte[chunkSize];
                numBytesRead = stream.read(chunk, 0, chunkSize);

                assertTrue(numBytesRead > 0);
                assertTrue(Arrays.equals(arr, position + i, position + i + numBytesRead, chunk, 0, numBytesRead));
            }

            assertEquals(-1, stream.read(new byte[capacity]));
        }
    }

    @Test
    @DisplayName("Reading into a buffer with a non-zero offset")
    void readWithOffsetAndLengthIntoBuffer() throws IOException {
        final var arr = TestUtils.randomBytes(87); // some random size and random bytes
        final var buf = BufferedData.wrap(arr);
        final var pos = 13; // Some random position. Leaves me with 74 bytes to read
        buf.skip(pos);

        try (final var stream = new KnownLengthStream(buf)) {
            // Make the buffer larger than needed, and then read into a section of the array
            final int chunkSize = 15;
            final byte[] chunk = new byte[chunkSize + 10];
            final int numBytesRead = stream.read(chunk, 5, chunkSize);
            assertEquals(chunkSize, numBytesRead);

            assertThat(Arrays.copyOfRange(chunk, 5, 5 + chunkSize))
                    .isEqualTo(Arrays.copyOfRange(arr, pos, pos + chunkSize));
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "readAllBytes".
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with readAllBytes")
    void readAllBytes(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);
        final var remaining = (int) buf.remaining();

        try (final var stream = new KnownLengthStream(buf)) {
            // The first time, there will be bytes
            final var allBytes = stream.readAllBytes();
            assertTrue(Arrays.equals(arr, position, position + remaining, allBytes, 0, allBytes.length));

            // Now we're at the end of the stream, so there will be no bytes
            assertEquals(0, stream.readAllBytes().length);
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "readNBytes".
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with readNBytes")
    void readNBytes(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);

        try (final var stream = new KnownLengthStream(buf)) {
            // Read the bytes from the stream in chunks, such that we get a chunk
            // that completely fits within the num bytes to read, and a chunk
            // that read the rest of the bytes but has some space remaining,
            // and then read when there are no bytes left to make sure we get
            // a -1
            int numBytesToRead = (int) buf.remaining();
            final int chunkSize = (int) (numBytesToRead * .75);
            int numBytesRead;
            for (int i = 0; i < numBytesToRead; i += numBytesRead) {
                assert chunkSize > 0;
                final byte[] chunk = stream.readNBytes(chunkSize);
                numBytesRead = chunk.length;

                assertTrue(numBytesRead > 0);
                assertTrue(Arrays.equals(arr, position + i, position + i + numBytesRead, chunk, 0, numBytesRead));
            }

            assertEquals(0, stream.readNBytes(1).length);
        }
    }

    /**
     * Given a {@link BufferedData} with the position set to some value (possibly 0, possibly another
     * value) and the capacity being some value (possibly 0), validate that we can read each byte
     * using the "readNBytes" method with offset, and len.
     *
     * @param capacity The number of bytes in the buffer
     * @param position The position within the buffer to start reading from
     * @throws IOException should not be thrown
     */
    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("All bytes from the buffer can be read with readNBytes(byte[], off, len)")
    void readNBytesOffLen(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);

        try (final var stream = new KnownLengthStream(buf)) {
            // Read the bytes from the stream in chunks, such that we get a chunk
            // that completely fits within the num bytes to read, and a chunk
            // that read the rest of the bytes but has some space remaining,
            // and then read when there are no bytes left to make sure we get
            // a -1
            int numBytesToRead = (int) buf.remaining();
            final int chunkSize = (int) (numBytesToRead * .75);
            int numBytesRead;
            for (int i = 0; i < numBytesToRead; i += numBytesRead) {
                assert chunkSize > 0;
                final byte[] chunk = new byte[chunkSize];
                numBytesRead = stream.readNBytes(chunk, 0, chunkSize);

                assertTrue(numBytesRead > 0);
                assertTrue(Arrays.equals(arr, position + i, position + i + numBytesRead, chunk, 0, numBytesRead));
            }

            assertEquals(0, stream.readNBytes(new byte[1], 0, 1));
        }
    }
}
