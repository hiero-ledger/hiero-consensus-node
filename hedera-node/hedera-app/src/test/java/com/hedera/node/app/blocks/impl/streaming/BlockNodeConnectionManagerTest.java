// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionManagerTest {
    private static final Duration INITIAL_DELAY = Duration.ofMillis(10);

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    ConfigProvider mockConfigProvider;

    @Mock
    private Supplier<Void> mockSupplier;

    @Mock
    BlockNodeConnection mockConnection;

    @Mock
    private BlockNodeConnectionConfig mockBlockNodeConnectionConfig;

    @Mock
    private BlockStreamConfig mockBlockStreamConfig;

    @Mock
    private BlockNodeConfigExtractor mockNodeConfigExtractor;

    @BeforeEach
    public void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockNode.blockNodeConnectionFileDir", "./src/test/resources/bootstrap")
                .getOrCreateConfig();
        given(mockConfigProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        blockNodeConnectionManager = new BlockNodeConnectionManager(mockConfigProvider);
    }

    @Test
    void testNewBlockNodeConnectionManager() {
        final var expectedGrpcEndpoint =
                BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
        assertEquals(expectedGrpcEndpoint, blockNodeConnectionManager.getGrpcEndPoint());
    }

    @Test
    void testRetry_SuccessOnFirstAttempt() {
        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(1)).get();
        assertThat(logCaptor.infoLogs()).contains("Retrying in 10 ms");
    }

    @Test
    void testRetry_SuccessOnRetry() {
        when(mockSupplier.get())
                .thenThrow(new RuntimeException("First attempt failed"))
                .thenReturn(null);

        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(2)).get();
        assertThat(logCaptor.infoLogs()).contains("Retrying in 10 ms", "Retrying in 20 ms");
    }

    @Test
    void testScheduleReconnect() throws InterruptedException {
        blockNodeConnectionManager.scheduleReconnect(mockConnection);

        verifyNoInteractions(mockConnection); // there should be no immediate attempt to establish a stream

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        assertThat(logCaptor.infoLogs()).contains("Retrying in 1000 ms");
        verify(mockConnection, times(1)).establishStream();
    }

    @Test
    void testEstablishConnections_PrioritizesNodes() {

        // Establishing connections indirectly via waitForConnections
        blockNodeConnectionManager.waitForConnections(Duration.ofSeconds(5));

        List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.get(0)).contains("Establishing connections to block nodes");

        // Verify the order of connection attempts: The high priority node should be the first
        assertThat(infoLogs.get(1)).contains("Connecting to block node localhost:8080");

        // The lower priority nodes (any of node2, node3, node4) should come after that
        assertThat(infoLogs.get(2))
                .matches(log -> log.contains("Connecting to block node node2.example.com:8080")
                        || log.contains("Connecting to block node node3.example.com:8081")
                        || log.contains("Connecting to block node node4.example.com:8081"));
    }
}
