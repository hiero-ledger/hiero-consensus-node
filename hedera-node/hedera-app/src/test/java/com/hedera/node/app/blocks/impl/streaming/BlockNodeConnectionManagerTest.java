// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.internal.network.BlockNodeConfig;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionManagerTest {
    private static final Duration INITIAL_DELAY = Duration.ofMillis(10);
    private static final long TEST_BLOCK_NUMBER = 42L;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private Supplier<Void> mockSupplier;

    @Mock
    private BlockNodeConnection mockConnection;

    @Mock
    private BlockStreamStateManager mockStateManager;

    @Mock
    private BlockState mockBlockState;

    @Mock
    BlockStreamMetrics blockStreamMetrics;

    private static BlockNodeConfigExtractor mockBlockNodeConfigExtractor;

    private static final List<Server> testServers = new ArrayList<>();

    @BeforeAll
    static void beforeAll() {
        mockBlockNodeConfigExtractor = mock(BlockNodeConfigExtractor.class);
        when(mockBlockNodeConfigExtractor.getAllNodes())
                .thenReturn(List.of(localBlockNodeConfigEntry(8080, 1), localBlockNodeConfigEntry(8081, 2)));

        mockBlockNodeConfigExtractor.getAllNodes().forEach(node -> {
            try {
                testServers.add(ServerBuilder.forPort(node.port())
                        .addService(new BlockStreamServiceTestImpl())
                        .build());
            } catch (Exception e) {
                throw new RuntimeException("Failed to start test server", e);
            }
        });

        testServers.forEach(server -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start test server", e);
            }
        });
    }

    @BeforeEach
    void setUp() {
        blockNodeConnectionManager =
                new BlockNodeConnectionManager(mockBlockNodeConfigExtractor, mockStateManager, blockStreamMetrics);
    }

    @AfterAll
    static void afterAll() {
        testServers.forEach(server -> {
            try {
                server.shutdownNow();
            } catch (Exception e) {
                throw new RuntimeException("Failed to stop test server", e);
            }
        });
    }

    @Test
    void testNewBlockNodeConnectionManager() {
        final var expectedGrpcEndpoint =
                BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
        assertEquals(expectedGrpcEndpoint, blockNodeConnectionManager.getGrpcEndPoint());
    }

    @Test
    @Disabled
    void testRetrySuccessOnFirstAttempt() {
        blockNodeConnectionManager.scheduleRetry(mockConnection);
        when(mockSupplier.get()).thenReturn(null);

        verify(mockSupplier, times(1)).get();

        assertThat(logCaptor.debugLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
    }

    @Test
    @Disabled
    void testRetrySuccessOnRetry() {
        when(mockSupplier.get())
                .thenThrow(new RuntimeException("First attempt failed"))
                .thenReturn(null);

        // blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(2)).get();
        assertThat(logCaptor.debugLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
        assertThat(logCaptor.debugLogs())
                .containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY.multipliedBy(2)));
    }

    @Test
    void testScheduleRetry_WithPriority() throws InterruptedException {
        when(mockConnection.getNodeConfig())
                .thenReturn(BlockNodeConfig.newBuilder()
                        .address("localhost")
                        .port(8080)
                        .priority(1)
                        .build());

        blockNodeConnectionManager.scheduleRetry(mockConnection);

        // There should be no immediate attempt to establish a stream
        verify(mockConnection, times(0)).establishStream();

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        final var retryLog = logCaptor.debugLogs().stream()
                .filter(log -> log.contains("Retrying in"))
                .findFirst();
        assertThat(retryLog).isNotEmpty();
        verify(mockConnection, times(1)).establishStream();
    }

    @Test
    void testScheduleRetry_WithoutPriority() throws InterruptedException {
        given(mockConnection.getNodeConfig())
                .willReturn(BlockNodeConfig.newBuilder().build());
        blockNodeConnectionManager.scheduleRetry(mockConnection);

        verify(mockConnection, never()).establishStream(); // there should be no immediate attempt to establish a stream
        final var initialDelay = BlockNodeConnectionManager.INITIAL_RETRY_DELAY;
        Thread.sleep(initialDelay.plusMillis(100));

        assertThat(logCaptor.debugLogs()).containsAnyElementsOf(generateExpectedRetryLogs(initialDelay));
        verify(mockConnection, times(1)).establishStream();
    }

    @Test
    void testEstablishConnection_PrioritizesNodes() {
        // Establishes connections indirectly via waitForConnections
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        final List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.get(0)).contains("Establishing connection to block node based on priorities");

        // Verify the order of connection attempts: The high priority node should be the first
        assertThat(infoLogs.get(1)).contains("Connecting to block node localhost:8080");
    }

    @Test
    void handleConnectionError_shouldScheduleRetryAndChooseHigherPriorityConnection() throws InterruptedException {
        // Given established connection
        blockNodeConnectionManager.selectBlockNodeForStreaming();

        final var activeConnection =
                blockNodeConnectionManager.getActiveConnection().orElseThrow();
        lenient().doReturn(TEST_BLOCK_NUMBER).when(spy(activeConnection)).getCurrentBlockNumber();

        assertThat(activeConnection.getNodeConfig().priority()).isEqualTo(1L);
        assertThat(activeConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);

        lenient().doReturn(mockBlockState).when(mockStateManager).getBlockState(TEST_BLOCK_NUMBER);

        // When connection error occurs
        blockNodeConnectionManager.handleConnectionError(activeConnection);

        final var newActiveConnection =
                blockNodeConnectionManager.getActiveConnection().get();

        assertThat(newActiveConnection.getNodeConfig().priority()).isEqualTo(2L);
        assertThat(newActiveConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        assertThat(activeConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.RETRYING);
        assertThat(blockNodeConnectionManager.isRetrying(activeConnection)).isTrue();

        // Verify that the first connection is with higher priority than
        // the new connection, and it is ready connection
        assertThat(blockNodeConnectionManager.getHighestPriorityReadyConnection())
                .isEqualTo(activeConnection);

        assertThat(blockNodeConnectionManager.isHigherPriorityReady(newActiveConnection))
                .isTrue();

        // Verify that the initial connection is scheduled for retry
        assertThat(logCaptor.debugLogs())
                .containsAnyElementsOf(generateExpectedRetryLogs((BlockNodeConnectionManager.INITIAL_RETRY_DELAY)));

        // Verify that we successfully connected to the fallback node
        assertThat(logCaptor.infoLogs())
                .contains(
                        "Connecting to block node localhost:8081",
                        "Successfully connected to block node localhost:8081");

        // There is higher priority ready connection, so we close the current active
        assertThat(newActiveConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.CLOSED);

        // Wait some time to see if we schedule it for retry
        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(5000));

        // Check if it is correctly scheduled for retry
        assertThat(newActiveConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.RETRYING);

        // Get the current active, which is our first established connection from the scenario
        final var currentActiveConnection =
                blockNodeConnectionManager.getActiveConnection().get();
        assertThat(currentActiveConnection.getNodeConfig().priority()).isEqualTo(1L);
        assertThat(currentActiveConnection.getConnectionState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);
    }

    private List<String> generateExpectedRetryLogs(Duration delay) {
        final long start = delay.toMillis() / 2;
        final long end = delay.toMillis();
        final List<String> logs = new ArrayList<>();
        for (long i = start; i <= end; i++) {
            logs.add(String.format("Retrying in %d ms", i));
        }

        return logs;
    }

    private static BlockNodeConfig localBlockNodeConfigEntry(int port, int priority) {
        return BlockNodeConfig.newBuilder()
                .address("localhost")
                .port(port)
                .priority(priority)
                .build();
    }

    private static class BlockStreamServiceTestImpl extends BlockStreamServiceGrpc.BlockStreamServiceImplBase {
        @Override
        public StreamObserver<PublishStreamRequest> publishBlockStream(
                StreamObserver<PublishStreamResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(PublishStreamRequest request) {
                    // no-op
                }

                @Override
                public void onError(Throwable t) {
                    // no-op
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
