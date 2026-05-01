// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Future.State;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerLoggingTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle isConnectionManagerActiveHandle;
    private static final VarHandle blockNodesHandle;

    private static final MethodHandle updateConnectionIfNeededHandle;

    static {
        try {
            final Class<BlockNodeConnectionManager> cls = BlockNodeConnectionManager.class;
            final Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());

            isConnectionManagerActiveHandle =
                    lookup.findVarHandle(cls, "isConnectionManagerActive", AtomicBoolean.class);
            blockNodesHandle = lookup.findVarHandle(cls, "nodes", ConcurrentMap.class);

            final Method updateConnectionIfNeeded = cls.getDeclaredMethod("updateConnectionIfNeeded");
            updateConnectionIfNeeded.setAccessible(true);
            updateConnectionIfNeededHandle = lookup.unreflect(updateConnectionIfNeeded);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long NODE_ID = 0;

    private BlockNodeConnectionManager connectionManager;
    private LogCaptor logCaptor;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private NetworkInfo networkInfo;
    private NodeInfo selfNodeInfo;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;
    private BlockNodeConfigService blockNodeConfigService;
    private ConfigProvider configProvider;

    @TempDir
    Path tempDir;

    @BeforeEach
    void beforeEach() {
        // Use a non-existent directory to prevent loading any existing block-nodes.json during tests
        configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        tempDir.toAbsolutePath().toString()));

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = mock(ExecutorService.class);
        blockNodeConfigService = mock(BlockNodeConfigService.class);
        blockingIoExecutorSupplier = () -> blockingIoExecutor;
        networkInfo = mock(NetworkInfo.class);
        selfNodeInfo = mock(NodeInfo.class);
        when(networkInfo.selfNodeInfo()).thenReturn(selfNodeInfo);
        when(selfNodeInfo.nodeId()).thenReturn(NODE_ID);
        connectionManager = new BlockNodeConnectionManager(
                configProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService);

        // Clear any nodes that might have been loaded
        blockNodes().clear();

        // Ensure manager is not active
        final AtomicBoolean isActive = isConnectionManagerActive();
        isActive.set(false);

        logCaptor = new LogCaptor(LogManager.getLogger(BlockNodeConnectionManager.class));

        resetMocks();
    }

    @AfterEach
    void afterEach() {
        logCaptor.stopCapture();
    }

    @Test
    void testSuppressedChangeDetectionLogs() throws Throwable {
        invoke_updateConnectionIfNeeded();

        List<String> infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(2);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: missing-active-connection)", 1);
        assertLogOccurrence(infoLogs, "No block node candidates found for selection criteria: AnyCriteria[]", 1);

        // invoke the update method multiple times again... there should still only be the two logs once
        invoke_updateConnectionIfNeeded();
        invoke_updateConnectionIfNeeded();
        invoke_updateConnectionIfNeeded();
        invoke_updateConnectionIfNeeded();

        infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(2);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: missing-active-connection)", 1);
        assertLogOccurrence(infoLogs, "No block node candidates found for selection criteria: AnyCriteria[]", 1);

        // now mock that the buffer is unhealthy
        // this should cause the logs to be written again
        when(bufferService.latestBufferStatus()).thenReturn(new BlockBufferStatus(Instant.now(), 80.0D, true));
        invoke_updateConnectionIfNeeded();

        infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(4);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: missing-active-connection)", 1);
        assertLogOccurrence(
                infoLogs,
                "Streaming connection update requested (reason: missing-active-connection buffer-unhealthy)",
                1);
        assertLogOccurrence(infoLogs, "No block node candidates found for selection criteria: AnyCriteria[]", 2);

        // now allow a new connection to be established
        // this should reset the suppressing of verbose change detection logs the next time a change is detected
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        when(blockNodeConfigService.latestConfiguration())
                .thenReturn(new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig)));
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class, (mock, context) -> {
                    when(mock.configuration()).thenReturn(nodeConfig);
                    when(mock.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1));
                    when(mock.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(180));
                    when(mock.connectionStatistics()).thenReturn(new StreamingConnectionStatistics());
                })) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);
        }

        infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(6);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: missing-active-connection)", 1);
        assertLogOccurrence(
                infoLogs,
                "Streaming connection update requested (reason: missing-active-connection buffer-unhealthy)",
                1);
        assertLogOccurrence(infoLogs, "No block node candidates found for selection criteria: AnyCriteria[]", 2);
        assertLogOccurrence(infoLogs, "[localhost:1234] Block node is available for streaming", 1);
        assertLogOccurrence(infoLogs, "Selected new block node for streaming: localhost:1234", 1);

        // invoke the update again... the buffer should still be unhealthy and it should trigger logging
        invoke_updateConnectionIfNeeded();
        invoke_updateConnectionIfNeeded(); // this should not trigger logging

        infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs).hasSize(8);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: missing-active-connection)", 1);
        assertLogOccurrence(
                infoLogs,
                "Streaming connection update requested (reason: missing-active-connection buffer-unhealthy)",
                1);
        assertLogOccurrence(infoLogs, "No block node candidates found for selection criteria: AnyCriteria[]", 2);
        assertLogOccurrence(infoLogs, "[localhost:1234] Block node is available for streaming", 1);
        assertLogOccurrence(infoLogs, "Selected new block node for streaming: localhost:1234", 1);
        assertLogOccurrence(infoLogs, "Streaming connection update requested (reason: buffer-unhealthy)", 1);
        assertLogOccurrence(infoLogs, "Selecting a new block node is deferred due to global cool down until", 1);
    }

    // Utilities

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

    void invoke_updateConnectionIfNeeded() throws Throwable {
        updateConnectionIfNeededHandle.invoke(connectionManager);
    }

    @SuppressWarnings("unchecked")
    ConcurrentMap<BlockNodeEndpoint, BlockNode> blockNodes() {
        return (ConcurrentMap<BlockNodeEndpoint, BlockNode>) blockNodesHandle.get(connectionManager);
    }

    AtomicBoolean isConnectionManagerActive() {
        return (AtomicBoolean) isConnectionManagerActiveHandle.get(connectionManager);
    }

    void resetMocks() {
        reset(bufferService, metrics, blockNodeConfigService);
    }
}
