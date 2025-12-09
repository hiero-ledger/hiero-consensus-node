// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

final class DataBufferMarshallerTest {
    private static final int MAX_MESSAGE_SIZE = 6144;
    private static final int BUFFER_CAPACITY = 133120;
    private static final int JUMBO_MAX_MESSAGE_SIZE = 133120;
    private final DataBufferMarshaller marshaller = new DataBufferMarshaller(BUFFER_CAPACITY, MAX_MESSAGE_SIZE);
    private final DataBufferMarshaller jumboMarshaller =
            new DataBufferMarshaller(JUMBO_MAX_MESSAGE_SIZE + 1, JUMBO_MAX_MESSAGE_SIZE);

    @Test
    void nullBufferThrows() {
        //noinspection resource,ConstantConditions
        assertThrows(NullPointerException.class, () -> marshaller.stream(null));
    }

    @Test
    void nullStreamThrows() {
        //noinspection ConstantConditions
        assertThrows(NullPointerException.class, () -> marshaller.parse(null));
    }

    private static Stream<Arguments> provideBuffers() {
        return Stream.of(Arguments.of(0, 0), Arguments.of(100, 0), Arguments.of(100, 80), Arguments.of(100, 100));
    }

    @ParameterizedTest(name = "A buffer with capacity {0} and position {1}")
    @MethodSource("provideBuffers")
    @DisplayName("ByteBuffer contents are streamed")
    void byteBufferContentsAreStreamed(int capacity, int position) throws IOException {
        final var arr = TestUtils.randomBytes(capacity);
        final var buf = BufferedData.wrap(arr);
        buf.skip(position);
        try (final var stream = marshaller.stream(buf)) {
            final var numBytesToRead = buf.remaining();
            for (int i = 0; i < numBytesToRead; i++) {
                assertEquals(Byte.toUnsignedInt(arr[i + position]), stream.read());
                assertEquals(numBytesToRead - i - 1, stream.available());
            }

            assertEquals(-1, stream.read());
        }
    }

    @Test
    @Disabled("I don't believe this test is valid")
    void callingStreamTwiceReturnsDifferentStreamsOnTheSameUnderlyingBuffer() throws IOException {
        final var arr = TestUtils.randomBytes(100);
        final var buf = BufferedData.wrap(arr);
        buf.skip(50);

        try (final var stream1 = marshaller.stream(buf);
                final var stream2 = marshaller.stream(buf)) {

            assertEquals(stream1.available(), stream2.available());
            assertNotEquals(-1, stream1.read());
            assertEquals(stream1.available(), stream2.available());

            assertEquals(49, stream2.skip(49));
            assertEquals(0, stream1.available());
        }
    }

    @Test
    void parseStream() {
        final var arr = TestUtils.randomBytes(100);
        final var stream = new ByteArrayInputStream(arr);
        final var buf = marshaller.parse(stream);

        assertEquals(arr.length, buf.remaining());
        for (byte b : arr) {
            assertEquals(b, buf.readByte());
        }
    }

    @ParameterizedTest(name = "With {0} bytes")
    @ValueSource(ints = {1024 * 6 + 1, 1024 * 1024})
    void parseStreamThatIsTooBig(int numBytes) {
        final var arr = TestUtils.randomBytes(numBytes);
        final var stream = new ByteArrayInputStream(arr);
        final var buff = marshaller.parse(stream);
        assertThat(buff.length()).isEqualTo(MAX_MESSAGE_SIZE + 1);
    }

    @Test
    void parseStreamThatFailsInTheMiddle() throws IOException {
        final var arr = TestUtils.randomBytes(100);
        try (final var stream = Mockito.mock(InputStream.class)) {
            Mockito.when(stream.read(Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                    .thenAnswer(invocation -> {
                        byte[] data = invocation.getArgument(0);
                        int offset = invocation.getArgument(1);
                        // Don't quite read everything
                        System.arraycopy(arr, 0, data, offset, 99);
                        return 99;
                    })
                    .thenThrow(new IOException("Stream Terminated unexpectedly"));

            assertThrows(RuntimeException.class, () -> marshaller.parse(stream));
        }
    }

    @Test
    void parseStreamThatTakesMultipleReads() throws IOException {
        final var arr = TestUtils.randomBytes(100);
        try (final var stream = Mockito.mock(InputStream.class)) {
            Mockito.when(stream.read(Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                    .thenAnswer(invocation -> {
                        byte[] data = invocation.getArgument(0);
                        int offset = invocation.getArgument(1);
                        // Don't quite read everything
                        System.arraycopy(arr, 0, data, offset, 50);
                        return 50;
                    })
                    .thenAnswer(invocation -> {
                        byte[] data = invocation.getArgument(0);
                        int offset = invocation.getArgument(1);
                        // Read the rest
                        System.arraycopy(arr, 50, data, offset, 50);
                        return 50;
                    })
                    .thenReturn(-1);

            final var buf = marshaller.parse(stream);
            assertEquals(arr.length, buf.remaining());
            for (byte b : arr) {
                assertEquals(b, buf.readByte());
            }
        }
    }

    @Test
    @DisplayName("Constructor throws when buffer capacity is less than max message size")
    void constructorThrowsWhenBufferCapacityLessThanMaxMessageSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataBufferMarshaller(100, 200),
                "Buffer capacity must be greater than or equal to the maximum message size.");
    }

    @Test
    @DisplayName("Constructor accepts buffer capacity equal to max message size")
    void constructorAcceptsEqualCapacityAndMaxSize() {
        final var m = new DataBufferMarshaller(100, 100);
        assertNotNull(m);
    }

    @Test
    @DisplayName("Constructor accepts buffer capacity greater than max message size")
    void constructorAcceptsGreaterCapacityThanMaxSize() {
        final var m = new DataBufferMarshaller(200, 100);
        assertNotNull(m);
    }

    @Test
    @DisplayName("Jumbo marshaller can parse stream within jumbo size limit")
    void jumboMarshallerParsesWithinLimit() {
        // Create a stream that is larger than regular limit but within jumbo limit
        final var arr = TestUtils.randomBytes(MAX_MESSAGE_SIZE + 1000);
        final var stream = new ByteArrayInputStream(arr);
        final var buf = jumboMarshaller.parse(stream);

        // Should read all bytes since it's within jumbo limit
        assertEquals(arr.length, buf.remaining());
        for (byte b : arr) {
            assertEquals(b, buf.readByte());
        }
    }

    @Test
    @DisplayName("Jumbo marshaller truncates stream exceeding jumbo size limit")
    void jumboMarshallerTruncatesExceedingLimit() {
        // Create a stream larger than jumbo limit
        final var arr = TestUtils.randomBytes(JUMBO_MAX_MESSAGE_SIZE + 5000);
        final var stream = new ByteArrayInputStream(arr);
        final var buff = jumboMarshaller.parse(stream);

        // Should truncate to jumbo max size + 1
        assertThat(buff.length()).isEqualTo(JUMBO_MAX_MESSAGE_SIZE + 1);
    }

    @Test
    @DisplayName("Regular marshaller truncates at regular limit while jumbo allows larger")
    void regularMarshallerTruncatesWhileJumboAllows() {
        // Create a stream that exceeds regular limit but is within jumbo limit
        final int size = MAX_MESSAGE_SIZE + 1000;
        final var arr = TestUtils.randomBytes(size);

        // Regular marshaller should truncate
        final var regularStream = new ByteArrayInputStream(arr);
        final var regularBuff = marshaller.parse(regularStream);
        assertThat(regularBuff.length()).isEqualTo(MAX_MESSAGE_SIZE + 1);

        // Jumbo marshaller should accept full size
        final var jumboStream = new ByteArrayInputStream(arr);
        final var jumboBuff = jumboMarshaller.parse(jumboStream);
        assertThat(jumboBuff.length()).isEqualTo(size);
    }

    @Test
    @DisplayName("Marshaller with capacity equal to max size plus one (NettyGrpcServerManager pattern)")
    void marshallerWithNettyGrpcServerManagerPattern() {
        final int maxTxnSize = 6144;
        final var m = new DataBufferMarshaller(MAX_MESSAGE_SIZE + 1, MAX_MESSAGE_SIZE);

        // Parse a stream exactly at the limit
        final var exactArr = TestUtils.randomBytes(MAX_MESSAGE_SIZE);
        final var exactStream = new ByteArrayInputStream(exactArr);
        final var exactBuff = m.parse(exactStream);
        assertEquals(MAX_MESSAGE_SIZE, exactBuff.remaining());

        // Parse a stream one byte over the limit - should read MAX_MESSAGE_SIZE + 1 bytes
        final var overArr = TestUtils.randomBytes(MAX_MESSAGE_SIZE + 100);
        final var overStream = new ByteArrayInputStream(overArr);
        final var overBuff = m.parse(overStream);
        assertThat(overBuff.length()).isEqualTo(maxTxnSize + 1);
    }
}
