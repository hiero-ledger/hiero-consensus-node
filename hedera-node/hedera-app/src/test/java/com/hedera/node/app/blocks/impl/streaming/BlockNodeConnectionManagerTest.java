// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
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
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Mock
    private Supplier<Void> mockSupplier;

    @Mock
    private BlockNodeConnection mockConnection;

    @Mock
    private BlockStreamStateManager mockStateManager;

    private static BlockNodeConfigExtractorImpl blockNodeConfigExtractorImpl;

    private static Server testServer;

    @BeforeAll
    static void beforeAll() throws IOException {
        blockNodeConfigExtractorImpl = new BlockNodeConfigExtractorImpl("./src/test/resources/bootstrap");
        final int testServerPort =
                blockNodeConfigExtractorImpl.getAllNodes().getFirst().port();
        testServer = ServerBuilder.forPort(testServerPort)
                .addService(new BlockStreamServiceTestImpl())
                .build();
        testServer.start();
    }

    @BeforeEach
    void setUp() {
        blockNodeConnectionManager = new BlockNodeConnectionManager(blockNodeConfigExtractorImpl, mockStateManager);
    }

    @AfterAll
    static void afterAll() {
        testServer.shutdownNow();
    }

    @Test
    void testNewBlockNodeConnectionManager() {
        final var expectedGrpcEndpoint =
                BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
        assertEquals(expectedGrpcEndpoint, blockNodeConnectionManager.getGrpcEndPoint());
    }

    @Test
    void testRetrySuccessOnFirstAttempt() {
        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(1)).get();

        assertThat(logCaptor.debugLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
    }

    @Test
    void testRetrySuccessOnRetry() {
        when(mockSupplier.get())
                .thenThrow(new RuntimeException("First attempt failed"))
                .thenReturn(null);

        blockNodeConnectionManager.retry(mockSupplier, INITIAL_DELAY);

        verify(mockSupplier, times(2)).get();
        assertThat(logCaptor.debugLogs()).containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY));
        assertThat(logCaptor.debugLogs())
                .containsAnyElementsOf(generateExpectedRetryLogs(INITIAL_DELAY.multipliedBy(2)));
    }

    @Test
    void testScheduleReconnect_WithPriority() throws InterruptedException {
        when(mockConnection.getNodeConfig())
                .thenReturn(BlockNodeConfig.newBuilder()
                        .address("localhost")
                        .port(8080)
                        .priority(1)
                        .build());

        blockNodeConnectionManager.scheduleReconnect(mockConnection);

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
    void testScheduleReconnect_WithoutPriority() throws InterruptedException {
        given(mockConnection.getNodeConfig())
                .willReturn(BlockNodeConfig.newBuilder().build());
        blockNodeConnectionManager.scheduleReconnect(mockConnection);

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
        assertThat(infoLogs.get(0)).contains("Establishing connection to primary block node");

        // Verify the order of connection attempts: The high priority node should be the first
        assertThat(infoLogs.get(1)).contains("Connecting to block node localhost:8080");
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
