// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io.extendable;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contains static methods for doing sanity checks on streams. Ensures that streams conform to basic expected behavior.
 */
public final class StreamSanityChecks {

    private StreamSanityChecks() {}

    /**
     * Assert that two array segments match.
     */
    private static void assertArraySegmentsMatch(
            final byte[] a, final int aOffset, final byte[] b, final int bOffset, final int length) {
        for (int index = 0; index < length; index++) {
            assertEquals(a[aOffset + index], b[bOffset + index], "byte does not match");
        }
    }

    /**
     * Tests {@link InputStream#read()}
     *
     * @param in
     * 		the input stream
     * @param expectedBytes
     * 		the bytes that are expected to come out of the stream
     * @param index
     * 		the index of the next byte to be read
     * @return the new index
     */
    private static int testRead(final InputStream in, final byte[] expectedBytes, final int index) throws IOException {
        final byte b = (byte) in.read();
        assertEquals(expectedBytes[index], b, "byte should match");

        return index + 1;
    }

    /**
     * Tests {@link InputStream#read(byte[], int, int)}
     *
     * @param random
     * 		a source of randomness
     * @param in
     * 		the input stream
     * @param expectedBytes
     * 		the bytes that are expected to come out of the stream
     * @param index
     * 		the index of the next byte to be read
     * @return the new index
     */
    private static int testReadIntoArray(
            final Random random, final InputStream in, final byte[] expectedBytes, final int index) throws IOException {

        final int bytesToRead = (int) (random.nextDouble() * (expectedBytes.length / 10));
        final int expectedBytesRead = Math.min(bytesToRead, expectedBytes.length - index);

        final int offset = (int) (random.nextDouble() * 10);

        final byte[] b = new byte[bytesToRead + offset];
        final int bytesActuallyRead = in.read(b, offset, bytesToRead);

        if (bytesActuallyRead == -1) {
            assertEquals(0, expectedBytesRead, "there should be no more bytes to read");
            return index;
        } else {
            assertEquals(expectedBytesRead, bytesActuallyRead, "expected number of bytes should have been read");
            assertArraySegmentsMatch(expectedBytes, index, b, offset, expectedBytesRead);
            return index + expectedBytesRead;
        }
    }

    /**
     * Tests {@link InputStream#readNBytes(int)}
     *
     * @param random
     * 		a source of randomness
     * @param in
     * 		the input stream
     * @param expectedBytes
     * 		the bytes that are expected to come out of the stream
     * @param index
     * 		the index of the next byte to be read
     * @return the new index
     */
    private static int testReadNBytes(
            final Random random, final InputStream in, final byte[] expectedBytes, final int index) throws IOException {
        final int bytesToRead = (int) (random.nextDouble() * (expectedBytes.length / 10));
        final int expectedBytesRead = Math.min(bytesToRead, expectedBytes.length - index);

        final byte[] b = in.readNBytes(bytesToRead);

        assertEquals(expectedBytesRead, b.length, "expected number of bytes should have been read");
        assertArraySegmentsMatch(expectedBytes, index, b, 0, expectedBytesRead);

        return index + expectedBytesRead;
    }

    /**
     * Tests {@link InputStream#readNBytes(byte[], int, int)}
     *
     * @param random
     * 		a source of randomness
     * @param in
     * 		the input stream
     * @param expectedBytes
     * 		the bytes that are expected to come out of the stream
     * @param index
     * 		the index of the next byte to be read
     * @return the new index
     */
    private static int testReadNBytesIntoArray(
            final Random random, final InputStream in, final byte[] expectedBytes, final int index) throws IOException {

        final int bytesToRead = (int) (random.nextDouble() * (expectedBytes.length / 10));
        final int expectedBytesRead = Math.min(bytesToRead, expectedBytes.length - index);

        final int offset = (int) (random.nextDouble() * 10);

        final byte[] b = new byte[bytesToRead + offset];
        final int bytesActuallyRead = in.readNBytes(b, offset, bytesToRead);

        assertEquals(expectedBytesRead, bytesActuallyRead, "expected number of bytes should have been read");
        assertArraySegmentsMatch(expectedBytes, index, b, offset, expectedBytesRead);

        return index + expectedBytesRead;
    }

    /**
     * Perform basic sanity checks on an input stream.
     *
     * @param streamBuilder
     * 		a method that takes an output stream and wraps it in another stream
     */
    public static void inputStreamSanityCheck(final Function<InputStream, InputStream> streamBuilder)
            throws IOException {

        final Random random = getRandomPrintSeed();

        final int dataSize = 1024;
        final int iterations = 1024;

        for (int iteration = 0; iteration < iterations; iteration++) {
            final byte[] bytes = new byte[dataSize];
            random.nextBytes(bytes);

            final ByteArrayInputStream baseIn = new ByteArrayInputStream(bytes);
            final InputStream in = streamBuilder.apply(baseIn);

            // The next index to be read from the stream
            int index = 0;

            // Until there is no data remaining, randomly read from the stream using the different methods available
            while (index < dataSize) {
                if (random.nextBoolean()) {
                    index = testRead(in, bytes, index);
                }
                if (random.nextBoolean()) {
                    index = testReadIntoArray(random, in, bytes, index);
                }
                if (random.nextBoolean()) {
                    index = testReadNBytes(random, in, bytes, index);
                }
                if (random.nextBoolean()) {
                    index = testReadNBytesIntoArray(random, in, bytes, index);
                }
            }

            // Check behavior after stream is closed
            assertEquals(-1, in.read(), "stream should be closed");
            assertEquals(-1, in.read(new byte[10], 0, 10), "stream should be closed");
            final byte[] b = in.readNBytes(100);
            assertEquals(0, b.length, "stream should have no bytes remaining");
            assertEquals(0, in.readNBytes(new byte[10], 0, 10), "stream should have no bytes remaining");

            in.close();
        }
    }

    public static void outputStreamSanityCheck(final Function<OutputStream, OutputStream> streamBuilder)
            throws IOException {

        final Random random = getRandomPrintSeed();

        final int dataSize = 1024;
        final int iterations = 1024;

        for (int iteration = 0; iteration < iterations; iteration++) {
            final byte[] bytes = new byte[dataSize];
            random.nextBytes(bytes);

            final ByteArrayOutputStream baseOut = new ByteArrayOutputStream();
            final OutputStream out = streamBuilder.apply(baseOut);

            // The next index to be written to the stream
            int index = 0;

            // Until there is no data remaining, randomly write into using the different methods available
            while (index < dataSize) {
                if (random.nextBoolean()) {
                    out.write(bytes[index++]);
                }
                if (random.nextBoolean()) {
                    final int bytesToWrite = Math.min(dataSize - index, (int) (random.nextDouble() * (dataSize / 10)));
                    out.write(bytes, index, bytesToWrite);
                    index += bytesToWrite;
                }
            }

            final byte[] resultingBytes = baseOut.toByteArray();
            assertArrayEquals(bytes, resultingBytes, "bytes should be the same after the stream");

            out.close();
        }
    }

    /**
     * The sanity checks should pass for an unmodified stream from the core java library.
     */
    @Test
    @DisplayName("inputStreamSanityCheck() Sanity Check")
    void inputStreamSanityCheckSanityCheck() throws IOException {
        inputStreamSanityCheck(stream -> stream);
    }

    /**
     * The sanity checks should pass for an unmodified stream from the core java library.
     */
    @Test
    @DisplayName("outputStreamSanityCheck() Sanity Check")
    void outputStreamSanityCheckSanityCheck() throws IOException {
        outputStreamSanityCheck(stream -> stream);
    }
}
