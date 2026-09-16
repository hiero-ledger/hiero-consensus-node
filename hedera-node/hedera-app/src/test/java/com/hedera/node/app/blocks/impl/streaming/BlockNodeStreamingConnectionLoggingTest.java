// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.BlockEndRequest;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStreamingConnection.StreamRequest;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.types.BlockStreamGrpcCompressionType;
import com.hedera.pbj.runtime.grpc.GrpcCall;
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
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeStreamingConnectionLoggingTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle connectionStateHandle;

    private static final MethodHandle sendRequestHandle;
    private static final MethodHandle handleEndOfStreamHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            final Class<?> cls = BlockNodeStreamingConnection.class;

            connectionStateHandle = MethodHandles.privateLookupIn(AbstractBlockNodeConnection.class, lookup)
                    .findVarHandle(AbstractBlockNodeConnection.class, "stateRef", AtomicReference.class);

            final Method sendRequest = cls.getDeclaredMethod("sendRequest", StreamRequest.class);
            sendRequest.setAccessible(true);
            sendRequestHandle = lookup.unreflect(sendRequest);

            final Method handleEndOfStream = cls.getDeclaredMethod("handleEndOfStream", EndOfStream.class);
            handleEndOfStream.setAccessible(true);
            handleEndOfStreamHandle = lookup.unreflect(handleEndOfStream);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long SLOW_REQ_THRESHOLD_MILLIS = 200;

    private BlockNodeStreamingConnection connection;
    private LogCaptor logCaptor;

    ExecutorService blockingIoExecutor;
    private GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> requestCall;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() {
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.slowRequestThresholdMillis", SLOW_REQ_THRESHOLD_MILLIS));
        final BlockNode blockNode = mock(BlockNode.class);
        lenient().when(blockNode.stats()).thenReturn(mock(BlockNodeStats.class));
        final BlockNodeConfiguration bnConfig = newBlockNodeConfig("localhost", 8080, 1);
        when(blockNode.configuration()).thenReturn(bnConfig);
        final BlockNodeConnectionManager connectionManager = mock(BlockNodeConnectionManager.class);
        final BlockBufferService bufferService = mock(BlockBufferService.class);
        final BlockStreamMetrics metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = Executors.newSingleThreadExecutor();
        final BlockNodeClientFactory clientFactory = mock(BlockNodeClientFactory.class);
        requestCall = mock(GrpcCall.class);
        final BlockStreamingObs streamingObs = mock(BlockStreamingObs.class);

        final BlockStreamPublishBytesClient client = mock(BlockStreamPublishBytesClient.class);
        when(clientFactory.createStreamingClient(
                        any(BlockNodeConfiguration.class),
                        any(Duration.class),
                        anyString(),
                        any(BlockStreamGrpcCompressionType.class)))
                .thenReturn(client);
        when(client.publishBlockStream(any(Pipeline.class))).thenReturn(requestCall);

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
                .when(requestCall)
                .sendRequest(any(), anyBoolean());

        final PublishStreamRequestBytes psr = PublishStreamRequestBytes.newBuilder()
                .endOfBlock(BlockEnd.newBuilder().blockNumber(1))
                .build();
        final StreamRequest request = new BlockEndRequest(psr, 1, 2, 3_000, 1);

        invoke_sendRequest(request);

        final List<String> warnLogs = logCaptor.warnLogs();
        assertLogOccurrence(
                warnLogs, "Slow request detected (threshold: " + SLOW_REQ_THRESHOLD_MILLIS + "ms, observed: ", 1);
    }

    @Test
    void testHandleEndOfStream() throws Throwable {
        connection.initialize();
        connectionState().set(ConnectionState.ACTIVE);

        final EndOfStream eos = EndOfStream.newBuilder()
                .blockNumber(14)
                .proximateBlockNumber(16)
                .status(EndOfStream.Code.BAD_BLOCK_PROOF)
                .build();

        invoke_handleEndOfStream(eos);

        final List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(2);

        assertThat(infoLogs.get(0))
                .contains(
                        "Received EndOfStream response (lastVerifiedBlock: 14, approxProblematicBlock: 16, responseCode: BAD_BLOCK_PROOF)");
        assertThat(infoLogs.get(1)).contains("Closing connection (reason: TRANSIENT_END_STREAM_RECEIVED,");
    }

    // Utilities

    @SuppressWarnings("unchecked")
    private AtomicReference<ConnectionState> connectionState() {
        return (AtomicReference<ConnectionState>) connectionStateHandle.get(connection);
    }

    private void invoke_sendRequest(final StreamRequest request) throws Throwable {
        sendRequestHandle.invoke(connection, request);
    }

    private void invoke_handleEndOfStream(final EndOfStream endOfStream) throws Throwable {
        handleEndOfStreamHandle.invoke(connection, endOfStream);
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
