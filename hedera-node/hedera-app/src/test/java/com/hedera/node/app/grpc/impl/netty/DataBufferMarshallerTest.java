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
    private final DataBufferMarshaller marshaller = new DataBufferMarshaller(BUFFER_CAPACITY, MAX_MESSAGE_SIZE);

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
}
