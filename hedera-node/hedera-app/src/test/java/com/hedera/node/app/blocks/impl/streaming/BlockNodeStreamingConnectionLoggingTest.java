// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.BlockEndRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.SendRequestResult;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.StreamRequest;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.grpc.Pipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeStreamingConnectionLoggingTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle connectionStateHandle;

    private static final MethodHandle sendRequestHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();

            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);

            final Method sendRequest =
                    BlockNodeStreamingConnection.class.getDeclaredMethod("sendRequest", StreamRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long SLOW_REQ_THRESHOLD_MILLIS = 200;

    private BlockNodeStreamingConnection connection;
    private LogCaptor logCaptor;

    ExecutorService blockingIoExecutor;
    private Pipeline<?> requestPipeline;

    @BeforeEach
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.slowRequestThresholdMillis", SLOW_REQ_THRESHOLD_MILLIS));
        final BlockNode blockNode = mock(BlockNode.class);
        final BlockNodeConfiguration bnConfig = newBlockNodeConfig("localhost", 8080, 1);
        when(blockNode.configuration()).thenReturn(bnConfig);
        final BlockNodeConnectionManager connectionManager = mock(BlockNodeConnectionManager.class);
        final BlockBufferService bufferService = mock(BlockBufferService.class);
        final BlockStreamMetrics metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = Executors.newSingleThreadExecutor();
        final BlockNodeClientFactory clientFactory = mock(BlockNodeClientFactory.class);
        requestPipeline = mock(Pipeline.class);
        final BlockStreamingObs streamingObs = mock(BlockStreamingObs.class);

        final BlockStreamPublishServiceClient client = mock(BlockStreamPublishServiceClient.class);
        when(clientFactory.createStreamingClient(any(BlockNodeConfiguration.class), any(Duration.class), anyString()))
                .thenReturn(client);
        when(client.publishBlockStream(any(Pipeline.class))).thenReturn(requestPipeline);

        connection = new BlockNodeStreamingConnection(
                configProvider,
                blockNode,
                connectionManager,
                bufferService,
                metrics,
                blockingIoExecutor,
                null,
                clientFactory,
                0L,
                streamingObs);

        logCaptor = new LogCaptor(LogManager.getLogger(BlockNodeStreamingConnection.class));
    }

    @AfterEach
    void afterEach() {
        if (blockingIoExecutor != null) {
            blockingIoExecutor.shutdownNow();
        }

        logCaptor.stopCapture();
    }

    @Test
    void testSlowConnectionWarning() throws Throwable {
        connection.initialize();
        connectionState().set(ConnectionState.ACTIVE);

        doAnswer(inv -> {
                    try {
                        Thread.sleep(SLOW_REQ_THRESHOLD_MILLIS + 100);
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    return null;
                })
                .when(requestPipeline)
                .onNext(any());

        final PublishStreamRequest psr = PublishStreamRequest.newBuilder()
                .endOfBlock(BlockEnd.newBuilder().blockNumber(1))
                .build();
        final StreamRequest request = new BlockEndRequest(psr, 1, 2);

        invoke_sendRequest(request);

        final List<String> warnLogs = logCaptor.warnLogs();
        assertLogOccurrence(
                warnLogs, "Slow request detected (threshold: " + SLOW_REQ_THRESHOLD_MILLIS + "ms, observed: ", 1);
    }

    // Utilities

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> connectionState() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }

    private SendRequestResult invoke_sendRequest(final StreamRequest request) throws Throwable {
        return (SendRequestResult) sendRequestHandle.invoke(connection, request);
    }

    void assertLogOccurrence(final List<String> logLines, final String expectedMessage, final int numExpected) {
        int numFound = 0;
        for (final String logLine : logLines) {
            if (logLine.contains(expectedMessage)) {
                ++numFound;
            }
        }

        assertThat(numFound)
                .overridingErrorMessage(
                        "Expected to find message '%s' %d times, but only found %d",
                        expectedMessage, numExpected, numFound)
                .isEqualTo(numExpected);
    }
}
