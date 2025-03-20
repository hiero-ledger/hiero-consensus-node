// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class DataBufferMarshallerTest {
    private static final int MAX_MESSAGE_SIZE = 6144;
    private static final ThreadLocal<BufferedData> BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> BufferedData.allocate(MAX_MESSAGE_SIZE + 1));

    private final VersionedConfiguration configuration =
            new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
    private final DataBufferMarshaller marshaller = new DataBufferMarshaller(() -> configuration);

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
    @DisplayName("Increase buffer size if jumbo transactions are enabled")
    void buildDefinitionWithJumboSizedMethod() {
        // enable jumbo transactions
        final var config = enableJumboTransactions();
        final var jumboTransactionSize =
                config.getConfigData(JumboTransactionsConfig.class).maxTxnSize();
        final var jumboMarshaller = new DataBufferMarshaller(() -> config);

        final var arr = TestUtils.randomBytes(1024 * 1024);
        final var stream = new ByteArrayInputStream(arr);

        final var jumboBuff = jumboMarshaller.parse(stream);
        // assert buffer size limits
        assertThat(jumboBuff.length()).isEqualTo(jumboTransactionSize + 1);
    }

    private VersionedConfiguration enableJumboTransactions() {
        final var spyConfig = spy(configuration);
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        final var jumboConfig = configuration.getConfigData(JumboTransactionsConfig.class);
        final var spyJumboConfig = spy(jumboConfig);
        when(spyConfig.getConfigData(JumboTransactionsConfig.class)).thenReturn(spyJumboConfig);
        when(spyConfig.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        when(spyJumboConfig.isEnabled()).thenReturn(true);
        return spyConfig;
    }
}
