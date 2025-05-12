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
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
    void testScheduleRetry_WithPriority() throws InterruptedException {
        when(mockConnection.getNodeConfig())
                .thenReturn(BlockNodeConfig.newBuilder()
                        .address("localhost")
                        .port(8080)
                        .priority(1)
                        .build());

        blockNodeConnectionManager.scheduleRetry(mockConnection, INITIAL_DELAY);

        // There should be no immediate attempt to establish a stream
        verify(mockConnection, times(0)).createRequestObserver();

        Thread.sleep(INITIAL_DELAY.plusMillis(100));

        final var retryLogs = logCaptor.warnLogs().stream()
                .map(log -> {
                    int index = log.lastIndexOf("Rescheduling in");
                    return index >= 0 ? log.substring(index) : "";
                })
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toList());
        assertThat(retryLogs).isNotEmpty();
    }

    @Test
    void testScheduleRetry_WithoutPriority() throws InterruptedException {
        given(mockConnection.getNodeConfig())
                .willReturn(BlockNodeConfig.newBuilder().build());
        final var initialDelay = INITIAL_DELAY;
        blockNodeConnectionManager.scheduleRetry(mockConnection, initialDelay);

        // There should be no immediate attempt to establish a stream
        verify(mockConnection, times(0)).createRequestObserver();

        Thread.sleep(initialDelay.plusMillis(100));

        final var retryLogs = logCaptor.warnLogs().stream()
                .map(log -> {
                    int index = log.lastIndexOf("Rescheduling in");
                    return index >= 0 ? log.substring(index) : "";
                })
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toList());

        assertThat(retryLogs).containsAnyElementsOf(generateExpectedRetryLogs(initialDelay.multipliedBy(2)));
    }

    @Test
    void testEstablishConnection_PrioritizesNodes() {
        // Establishes connections indirectly via waitForConnections
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(5));

        final List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.get(0)).contains("Establishing connection to block node based on priorities");

        // Verify the order of connection attempts: The high priority node should be the first
        assertThat(infoLogs.get(1)).contains("Scheduling connection attempt for block node localhost:8080");
    }

    @Test
    void handleConnectionError_shouldScheduleRetryAndChooseHigherPriorityConnection() throws InterruptedException {
        // Given established connection
        blockNodeConnectionManager.selectBlockNodeForStreaming();

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        final var activeConnection = blockNodeConnectionManager.getActiveConnection();
        lenient().doReturn(TEST_BLOCK_NUMBER).when(spy(activeConnection)).getCurrentBlockNumber();
        assertThat(activeConnection.getNodeConfig().priority()).isEqualTo(1L);
        assertThat(activeConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);

        lenient().doReturn(mockBlockState).when(mockStateManager).getBlockState(TEST_BLOCK_NUMBER);

        List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.get(0)).contains("Establishing connection to block node based on priorities");
        assertThat(infoLogs.get(1)).contains("Scheduling connection attempt for block node localhost:8080");
        assertThat(infoLogs.get(2)).contains("Scheduling connection task for block node localhost:8080 in 0 ms");

        // When connection error occurs
        blockNodeConnectionManager.handleConnectionError(activeConnection);

        infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.get(3)).contains("Scheduling connection task for block node localhost:8080 in 1000 ms");

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        final var newActiveConnection = blockNodeConnectionManager.getActiveConnection();
        assertThat(newActiveConnection.getNodeConfig().priority()).isEqualTo(2L);
        assertThat(newActiveConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);

        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(100));

        assertThat(activeConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.PENDING);

        // Verify that the first connection is with higher priority than
        // the new connection, and it is ready connection
        assertThat(blockNodeConnectionManager.getHighestPriorityPendingConnection(newActiveConnection))
                .isEqualTo(activeConnection);

        assertThat(blockNodeConnectionManager.higherPriorityStarted(newActiveConnection))
                .isTrue();

        infoLogs = logCaptor.infoLogs();
        // Verify that we establish connection to the fallback node with priority 2
        assertThat(infoLogs.get(4)).contains("Establishing connection to block node based on priorities");
        assertThat(infoLogs.get(5)).contains("Scheduling connection attempt for block node localhost:8081");
        assertThat(infoLogs.get(6)).contains("Scheduling connection task for block node localhost:8081 in 0 ms");

        // There is higher priority ready connection, so we close the current active
        assertThat(newActiveConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.UNINITIALIZED);

        // Wait some time to see if we schedule it for retry
        Thread.sleep(BlockNodeConnectionManager.INITIAL_RETRY_DELAY.plusMillis(5000));

        // Check if it is correctly scheduled for retry
        assertThat(newActiveConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.UNINITIALIZED);

        // Get the current active, which is our first established connection from the scenario
        final var currentActiveConnection = blockNodeConnectionManager.getActiveConnection();
        assertThat(currentActiveConnection.getNodeConfig().priority()).isEqualTo(1L);

        assertThat(currentActiveConnection.getState()).isEqualTo(BlockNodeConnection.ConnectionState.ACTIVE);
    }

    private List<String> generateExpectedRetryLogs(Duration delay) {
        final long start = delay.toMillis() / 2;
        final long end = delay.toMillis();
        final List<String> logs = new ArrayList<>();
        for (long i = start; i <= end; i++) {
            logs.add(String.format("Rescheduling in %d ms", i));
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

    @Test
    void testUpdateLastVerifiedBlock_WhenNewBlockNumberIsGreater() {
        // Given
        final var blockNodeConfig = localBlockNodeConfigEntry(8080, 1);
        final var initialBlockNumber = 10L;
        final var newBlockNumber = 20L;

        // Set initial block number
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, initialBlockNumber);

        // When
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, newBlockNumber);

        // Then
        assertThat(blockNodeConnectionManager.getLastVerifiedBlock(blockNodeConfig))
                .isEqualTo(newBlockNumber);
        verify(mockStateManager).setLatestAcknowledgedBlock(newBlockNumber);
    }

    @Test
    void testUpdateLastVerifiedBlock_WhenNewBlockNumberIsLess() {
        // Given
        final var blockNodeConfig = localBlockNodeConfigEntry(8080, 1);
        final var initialBlockNumber = 20L;
        final var newBlockNumber = 10L;

        // Set initial block number
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, initialBlockNumber);

        // When
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, newBlockNumber);

        // Then
        assertThat(blockNodeConnectionManager.getLastVerifiedBlock(blockNodeConfig))
                .isEqualTo(initialBlockNumber);
        verify(mockStateManager).setLatestAcknowledgedBlock(newBlockNumber);
    }

    @Test
    void testUpdateLastVerifiedBlock_WhenNewBlockNumberIsEqual() {
        // Given
        final var blockNodeConfig = localBlockNodeConfigEntry(8080, 1);
        final var blockNumber = 20L;

        // Set initial block number
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, blockNumber);

        // When
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, blockNumber);

        // Then
        assertThat(blockNodeConnectionManager.getLastVerifiedBlock(blockNodeConfig))
                .isEqualTo(blockNumber);
        // Verify it was called only once (from the initial setup)
        verify(mockStateManager, times(2)).setLatestAcknowledgedBlock(blockNumber);
    }

    @Test
    void testUpdateLastVerifiedBlock_WhenNewBlockNumberIsNull() {
        // Given
        final var blockNodeConfig = localBlockNodeConfigEntry(8080, 1);
        final var initialBlockNumber = 20L;

        // Set initial block number
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, initialBlockNumber);

        // When
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, null);

        // Then
        assertThat(blockNodeConnectionManager.getLastVerifiedBlock(blockNodeConfig))
                .isEqualTo(initialBlockNumber);
        verify(mockStateManager, times(1)).setLatestAcknowledgedBlock(initialBlockNumber);
    }
}
