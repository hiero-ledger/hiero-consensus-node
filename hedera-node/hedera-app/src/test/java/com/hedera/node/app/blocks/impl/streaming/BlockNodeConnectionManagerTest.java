// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferSaturationInfo.SaturationDetail;
import com.hedera.node.app.blocks.impl.streaming.BlockNode.BlockNodeOutOfRange;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.AnyCriteria;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.BlockNodeResult;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.GroupSelectionOutcome;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.MinimumPriorityCriteria;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.NodeCandidate;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.NodeSelectionCriteria;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.ObservingConnectionsUpdateResult;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetrieveBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.SortedBlockNodes;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Future.State;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle isConnectionManagerActiveHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle blockNodesHandle;
    private static final VarHandle globalActiveStreamingConnectionCountHandle;
    private static final VarHandle activeConfigRefHandle;
    private static final VarHandle globalCoolDownTimestampRefHandle;
    private static final VarHandle bufferStatusRefHandle;
    private static final VarHandle connectionMonitorThreadRefHandle;
    private static final VarHandle activeObservingConnectionsHandle;
    private static final VarHandle idealObserversLastAttemptTimestampRefHandle;

    private static final VarHandle retrieveBlockNodeStatusTask_svcConnectionHandle;

    private static final MethodHandle isActiveConnectionAutoResetHandle;
    private static final MethodHandle isHigherPriorityNodeAvailableHandle;
    private static final MethodHandle isBufferUnhealthyHandle;
    private static final MethodHandle isConfigUpdatedHandle;
    private static final MethodHandle isMissingActiveStreamingConnectionHandle;
    private static final MethodHandle findAvailableStreamingNodeHandle;
    private static final MethodHandle getNextPriorityStreamingBlockNodeHandle;
    private static final MethodHandle selectNewStreamingBlockNodeHandle;
    private static final MethodHandle pruneNodesHandle;
    private static final MethodHandle updateConnectionIfNeededHandle;
    private static final MethodHandle fetchBlockNodeStatusHandle;
    private static final MethodHandle isBlockNodeWithinRangeHandle;
    private static final MethodHandle sortBlockNodesHandle;
    private static final MethodHandle tryNewObservingConnectionHandle;
    private static final MethodHandle tryUpdateNewStreamingConnectionHandle;
    private static final MethodHandle maybeUpdateObservingConnectionsHandle;
    private static final MethodHandle refreshObservingConnectionsHandle;
    private static final MethodHandle pruneExcessObserversHandle;
    private static final MethodHandle isObservingUpdateNeededHandle;

    static {
        try {
            final Class<BlockNodeConnectionManager> cls = BlockNodeConnectionManager.class;
            final Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            isConnectionManagerActiveHandle =
                    lookup.findVarHandle(cls, "isConnectionManagerActive", AtomicBoolean.class);
            activeConnectionRefHandle =
                    lookup.findVarHandle(cls, "activeStreamingConnectionRef", AtomicReference.class);
            blockNodesHandle = lookup.findVarHandle(cls, "nodes", ConcurrentMap.class);
            globalActiveStreamingConnectionCountHandle =
                    lookup.findVarHandle(cls, "globalActiveStreamingConnectionCount", AtomicInteger.class);
            activeConfigRefHandle = lookup.findVarHandle(cls, "activeConfigRef", AtomicReference.class);
            globalCoolDownTimestampRefHandle =
                    lookup.findVarHandle(cls, "globalCoolDownTimestampRef", AtomicReference.class);
            bufferStatusRefHandle = lookup.findVarHandle(cls, "bufferSaturationRef", AtomicReference.class);
            connectionMonitorThreadRefHandle =
                    lookup.findVarHandle(cls, "connectionMonitorThreadRef", AtomicReference.class);
            activeObservingConnectionsHandle =
                    lookup.findVarHandle(cls, "activeObservingConnections", ConcurrentMap.class);
            idealObserversLastAttemptTimestampRefHandle =
                    lookup.findVarHandle(cls, "idealObserversLastAttemptTimestampRef", AtomicReference.class);

            retrieveBlockNodeStatusTask_svcConnectionHandle = lookup.findVarHandle(
                    RetrieveBlockNodeStatusTask.class, "svcConnection", BlockNodeServiceConnection.class);

            final Method isActiveConnectionAutoReset = cls.getDeclaredMethod(
                    "isActiveConnectionAutoReset", Instant.class, BlockNodeStreamingConnection.class);
            isActiveConnectionAutoReset.setAccessible(true);
            isActiveConnectionAutoResetHandle = lookup.unreflect(isActiveConnectionAutoReset);

            final Method isHigherPriorityNodeAvailable =
                    cls.getDeclaredMethod("isHigherPriorityNodeAvailable", BlockNodeStreamingConnection.class);
            isHigherPriorityNodeAvailable.setAccessible(true);
            isHigherPriorityNodeAvailableHandle = lookup.unreflect(isHigherPriorityNodeAvailable);

            final Method isBufferUnhealthy = cls.getDeclaredMethod("isBufferUnhealthy");
            isBufferUnhealthy.setAccessible(true);
            isBufferUnhealthyHandle = lookup.unreflect(isBufferUnhealthy);

            final Method isConfigUpdated = cls.getDeclaredMethod("isConfigUpdated");
            isConfigUpdated.setAccessible(true);
            isConfigUpdatedHandle = lookup.unreflect(isConfigUpdated);

            final Method isMissingActiveStreamingConnection =
                    cls.getDeclaredMethod("isMissingActiveStreamingConnection", BlockNodeStreamingConnection.class);
            isMissingActiveStreamingConnection.setAccessible(true);
            isMissingActiveStreamingConnectionHandle = lookup.unreflect(isMissingActiveStreamingConnection);

            final Method findAvailableStreamingNode = cls.getDeclaredMethod("findAvailableStreamingNode", List.class);
            findAvailableStreamingNode.setAccessible(true);
            findAvailableStreamingNodeHandle = lookup.unreflect(findAvailableStreamingNode);

            final Method getNextPriorityStreamingBlockNode =
                    cls.getDeclaredMethod("getNextPriorityStreamingBlockNode", List.class);
            getNextPriorityStreamingBlockNode.setAccessible(true);
            getNextPriorityStreamingBlockNodeHandle = lookup.unreflect(getNextPriorityStreamingBlockNode);

            final Method selectNewStreamingBlockNode = cls.getDeclaredMethod(
                    "selectNewStreamingBlockNode",
                    boolean.class,
                    NodeSelectionCriteria.class,
                    CloseReason.class,
                    BlockNodeStreamingConnection.class);
            selectNewStreamingBlockNode.setAccessible(true);
            selectNewStreamingBlockNodeHandle = lookup.unreflect(selectNewStreamingBlockNode);

            final Method pruneNodes = cls.getDeclaredMethod("pruneNodes");
            pruneNodes.setAccessible(true);
            pruneNodesHandle = lookup.unreflect(pruneNodes);

            final Method updateConnectionIfNeeded = cls.getDeclaredMethod("updateConnectionIfNeeded");
            updateConnectionIfNeeded.setAccessible(true);
            updateConnectionIfNeededHandle = lookup.unreflect(updateConnectionIfNeeded);

            final Method fetchBlockNodeStatus = cls.getDeclaredMethod("fetchBlockNodeStatus", List.class);
            fetchBlockNodeStatus.setAccessible(true);
            fetchBlockNodeStatusHandle = lookup.unreflect(fetchBlockNodeStatus);

            final Method isBlockNodeWithinRange = cls.getDeclaredMethod(
                    "isBlockNodeWithinRange", BlockNode.class, long.class, long.class, long.class);
            isBlockNodeWithinRange.setAccessible(true);
            isBlockNodeWithinRangeHandle = lookup.unreflect(isBlockNodeWithinRange);

            final Method sortBlockNodes = cls.getDeclaredMethod("sortBlockNodes", Map.class);
            sortBlockNodes.setAccessible(true);
            sortBlockNodesHandle = lookup.unreflect(sortBlockNodes);

            final Method tryNewObservingConnection =
                    cls.getDeclaredMethod("tryNewObservingConnection", BlockNode.class);
            tryNewObservingConnection.setAccessible(true);
            tryNewObservingConnectionHandle = lookup.unreflect(tryNewObservingConnection);

            final Method tryUpdateNewStreamingConnection = cls.getDeclaredMethod(
                    "tryUpdateNewStreamingConnection",
                    BlockNode.class,
                    CloseReason.class,
                    BlockNodeStreamingConnection.class);
            tryUpdateNewStreamingConnection.setAccessible(true);
            tryUpdateNewStreamingConnectionHandle = lookup.unreflect(tryUpdateNewStreamingConnection);

            final Method maybeUpdateObservingConnection =
                    cls.getDeclaredMethod("maybeUpdateObservingConnections", List.class, int.class);
            maybeUpdateObservingConnection.setAccessible(true);
            maybeUpdateObservingConnectionsHandle = lookup.unreflect(maybeUpdateObservingConnection);

            final Method refreshObservingConnections = cls.getDeclaredMethod("refreshObservingConnections");
            refreshObservingConnections.setAccessible(true);
            refreshObservingConnectionsHandle = lookup.unreflect(refreshObservingConnections);

            final Method pruneExcessObservers = cls.getDeclaredMethod("pruneExcessObservers", int.class);
            pruneExcessObservers.setAccessible(true);
            pruneExcessObserversHandle = lookup.unreflect(pruneExcessObservers);

            final Method isObservingUpdateNeeded = cls.getDeclaredMethod("isObservingUpdateNeeded");
            isObservingUpdateNeeded.setAccessible(true);
            isObservingUpdateNeededHandle = lookup.unreflect(isObservingUpdateNeeded);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long NODE_ID = 0;

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private NetworkInfo networkInfo;
    private NodeInfo selfNodeInfo;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;
    private BlockNodeConfigService blockNodeConfigService;
    private ConfigProvider configProvider;
    private BlockStreamingObs streamingObs;

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
        streamingObs = mock(BlockStreamingObs.class);
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
                blockNodeConfigService,
                streamingObs);

        // Clear any nodes that might have been loaded
        blockNodes().clear();

        // Ensure manager is not active
        final AtomicBoolean isActive = isConnectionManagerActive();
        isActive.set(false);

        resetMocks();
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        // Tests that start the manager spawn a real 'bn-conn-monitor' thread; if it outlives this class
        // it keeps calling disabled mocks and floods the logger, breaking LogCaptor assertions elsewhere.
        isConnectionManagerActive().set(false);
        final Thread monitorThread = connectionMonitorThreadRef().getAndSet(null);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread.join(5_000);
        }
    }

    @Test
    void testIsActiveConnectionAutoReset_nullConnection() throws Throwable {
        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(Instant.now(), null);
        assertThat(isAutoReset).isFalse();
    }

    @Test
    void testIsActiveConnectionAutoReset_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // the auto reset timestamp is set to be in the future, thus the connection isn't ready to be reset
        when(activeConnection.autoResetTimestamp()).thenReturn(now.plusSeconds(30));

        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(now, activeConnection);

        assertThat(isAutoReset).isFalse();

        verify(activeConnection).autoResetTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsActiveConnectionAutoReset_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // the auto reset timestamp is set to be in the future, thus the connection isn't ready to be reset
        when(activeConnection.autoResetTimestamp()).thenReturn(now.minusSeconds(30));

        final boolean isAutoReset = invoke_isActiveConnectionAutoReset(now, activeConnection);

        assertThat(isAutoReset).isTrue();

        verify(activeConnection).autoResetTimestamp();
        verify(activeConnection).closeAtBlockBoundary(CloseReason.PERIODIC_RESET);
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsHigherPriorityNodeAvailable_nullConnection() throws Throwable {
        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(null);
        assertThat(isHigherAvailable).isFalse();
    }

    @Test
    void testIsHigherPriorityNodeAvailable_false() throws Throwable {
        final BlockNodeConfiguration activeConfig = newBlockNodeConfig("localhost", 9999, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(activeConfig);
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());
        when(activeConnection.currentState()).thenReturn(ConnectionState.ACTIVE);

        blockNodes().clear();
        final BlockNode priority2Node = new BlockNode(
                configProvider,
                newBlockNodeConfig("localhost", 1234, 2),
                new AtomicInteger(),
                new AtomicInteger(),
                new BlockNodeStats());
        final BlockNode activeNode = new BlockNode(
                configProvider, activeConfig, new AtomicInteger(), new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), priority2Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isFalse();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verify(activeConnection).connectionStatistics();
        verify(activeConnection).currentState();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsHigherPriorityNodeAvailable_true() throws Throwable {
        final BlockNodeConfiguration activeConfig = newBlockNodeConfig("localhost", 9999, 2);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(activeConfig);
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());
        when(activeConnection.currentState()).thenReturn(ConnectionState.ACTIVE);

        blockNodes().clear();
        final BlockNode priority1Node = new BlockNode(
                configProvider,
                newBlockNodeConfig("localhost", 1234, 1),
                new AtomicInteger(),
                new AtomicInteger(),
                new BlockNodeStats());
        final BlockNode activeNode = new BlockNode(
                configProvider, activeConfig, new AtomicInteger(), new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(priority1Node.configuration().streamingEndpoint(), priority1Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isTrue();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection).connectionStatistics();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verify(activeConnection).currentState();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsBufferUnhealthy_latestStatusNull() throws Throwable {
        when(bufferService.latestBufferSaturationInfo()).thenReturn(null);

        final boolean isBufferUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isBufferUnhealthy).isFalse();

        verify(bufferService).latestBufferSaturationInfo();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_sameStatus() throws Throwable {
        final BlockBufferSaturationInfo status = buildSaturationInfo(Instant.now(), 5.00D, false);
        when(bufferService.latestBufferSaturationInfo()).thenReturn(status);
        bufferStatusRef().set(status);

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();

        verify(bufferService).latestBufferSaturationInfo();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferSaturationInfo latestStatus = buildSaturationInfo(now, 5.00D, false);
        when(bufferService.latestBufferSaturationInfo()).thenReturn(latestStatus);
        bufferStatusRef().set(buildSaturationInfo(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferSaturationInfo();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferSaturationInfo latestStatus = buildSaturationInfo(now, 80.00D, true);
        when(bufferService.latestBufferSaturationInfo()).thenReturn(latestStatus);
        bufferStatusRef().set(buildSaturationInfo(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isTrue();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferSaturationInfo();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_unackedBlocks() throws Throwable {
        final BlockBufferSaturationInfo saturationInfoA = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(25.0D, false, 25, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoA);
        bufferStatusRef().set(BlockBufferSaturationInfo.NIL);

        final boolean isUnhealthyA = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyA).isFalse();

        // transition from healthy to action stage
        final BlockBufferSaturationInfo saturationInfoB = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(80.0D, true, 80, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoB);

        final boolean isUnhealthyB = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyB).isTrue();

        // transition to higher saturation
        final BlockBufferSaturationInfo saturationInfoC = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(85.0D, true, 85, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoC);

        final boolean isUnhealthyC = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyC).isTrue();

        // transition from action stage to below action stage
        final BlockBufferSaturationInfo saturationInfoD = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(10.0D, false, 10, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoD);

        final boolean isUnhealthyD = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyD).isFalse();

        // transition to higher saturation but still below action stage
        final BlockBufferSaturationInfo saturationInfoE = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(20.0D, false, 20, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoE);

        final boolean isUnhealthyE = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyE).isFalse();
    }

    @Test
    void testIsBufferUnhealthy_unackedBytes() throws Throwable {
        final BlockBufferSaturationInfo saturationInfoA = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(25.0D, false, 25, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoA);
        bufferStatusRef().set(BlockBufferSaturationInfo.NIL);

        final boolean isUnhealthyA = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyA).isFalse();

        // transition from healthy to action stage
        final BlockBufferSaturationInfo saturationInfoB = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(80.0D, true, 80, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoB);

        final boolean isUnhealthyB = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyB).isTrue();

        // transition to higher saturation
        final BlockBufferSaturationInfo saturationInfoC = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(85.0D, true, 85, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoC);

        final boolean isUnhealthyC = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyC).isTrue();

        // transition from action stage to below action stage
        final BlockBufferSaturationInfo saturationInfoD = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(10.0D, false, 10, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoD);

        final boolean isUnhealthyD = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyD).isFalse();

        // transition to higher saturation but still below action stage
        final BlockBufferSaturationInfo saturationInfoE = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(20.0D, false, 20, 100),
                new SaturationDetail(0.0D, false, 0, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfoE);

        final boolean isUnhealthyE = invoke_isBufferUnhealthy();
        assertThat(isUnhealthyE).isFalse();
    }

    @Test
    void testIsBufferUnhealthy_inProgressBlocksDoesNothing() throws Throwable {
        final BlockBufferSaturationInfo saturationInfo = new BlockBufferSaturationInfo(
                Instant.now(),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(0.0D, false, 0, 100),
                new SaturationDetail(100.0D, true, 5, 5));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(saturationInfo);

        final boolean isUnhealthy = invoke_isBufferUnhealthy();
        assertThat(isUnhealthy).isFalse();
    }

    @Test
    void testIsConfigUpdated_configRemoved() throws Throwable {
        final VersionedBlockNodeConfigurationSet activeConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(newBlockNodeConfig("localhost", 1234, 1)));
        activeConfigRef().set(activeConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(null);
        final BlockNode existingNode = mock(BlockNode.class);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), existingNode);

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isTrue();
        assertThat(activeConfigRef()).hasNullValue();

        verify(blockNodeConfigService).latestConfiguration();
        verify(existingNode).onTerminate(CloseReason.CONFIG_UPDATE);
        verifyNoMoreInteractions(blockNodeConfigService);
        verifyNoMoreInteractions(existingNode);
    }

    @Test
    void testIsConfigUpdated_configUpdated() throws Throwable {
        final BlockNodeConfiguration node1OldConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeConfiguration node2OldConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNodeConfiguration node1NewConfig = newBlockNodeConfig("localhost", 1234, 2);
        final BlockNodeConfiguration node3NewConfig = newBlockNodeConfig("localhost", 7890, 1);
        final VersionedBlockNodeConfigurationSet activeConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(node1OldConfig, node2OldConfig));
        // The new config updates localhost:1234 to priority 2, removes localhost:2345, and adds localhost:7890
        final VersionedBlockNodeConfigurationSet newConfig =
                new VersionedBlockNodeConfigurationSet(2, List.of(node1NewConfig, node3NewConfig));
        activeConfigRef().set(activeConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(newConfig);
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNode node2 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(node1OldConfig);
        when(node2.configuration()).thenReturn(node2OldConfig);
        blockNodes().put(node1OldConfig.streamingEndpoint(), node1);
        blockNodes().put(node2OldConfig.streamingEndpoint(), node2);

        final boolean isUpdated = invoke_isConfigUpdated();

        final ConcurrentMap<BlockNodeEndpoint, BlockNode> nodes = blockNodes();
        assertThat(nodes)
                .hasSize(3)
                .containsOnlyKeys(
                        node1NewConfig.streamingEndpoint(),
                        node2OldConfig.streamingEndpoint(),
                        node3NewConfig.streamingEndpoint());

        assertThat(isUpdated).isTrue();
        assertThat(activeConfigRef()).hasValue(newConfig);

        verify(node1).onConfigUpdate(node1NewConfig);
        verify(node2).onTerminate(CloseReason.CONFIG_UPDATE);
        verify(blockNodeConfigService).latestConfiguration();
        verifyNoMoreInteractions(node1);
        verifyNoMoreInteractions(node2);
        verifyNoMoreInteractions(blockNodeConfigService);
    }

    @Test
    void testIsConfigUpdated_configUpdatedWithNoChanges() throws Throwable {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet existingConfig =
                new VersionedBlockNodeConfigurationSet(1, List.of(config));
        final VersionedBlockNodeConfigurationSet newConfig = new VersionedBlockNodeConfigurationSet(2, List.of(config));
        activeConfigRef().set(existingConfig);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(newConfig);
        blockNodes()
                .put(
                        config.streamingEndpoint(),
                        new BlockNode(
                                configProvider,
                                config,
                                new AtomicInteger(),
                                new AtomicInteger(),
                                new BlockNodeStats()));

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isFalse();

        assertThat(activeConfigRef()).hasValue(existingConfig);
        assertThat(blockNodes()).hasSize(1).containsKey(config.streamingEndpoint());
    }

    @Test
    void testIsConfigUpdated_noConfigs() throws Throwable {
        activeConfigRef().set(null);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(null);

        final boolean isUpdated = invoke_isConfigUpdated();

        assertThat(isUpdated).isFalse();
    }

    @Test
    void testIsMissingActiveConnection_false() throws Throwable {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final boolean isMissing = invoke_isMissingActiveStreamingConnection(activeConnection);
        assertThat(isMissing).isFalse();
    }

    @Test
    void testIsMissingActiveConnection_true() throws Throwable {
        final boolean isMissing = invoke_isMissingActiveStreamingConnection(null);
        assertThat(isMissing).isTrue();
    }

    @Test
    void testNotifyConnectionActive_untrackedNode() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        blockNodes().clear();

        connectionManager.notifyConnectionActive(connection);

        verify(connection).configuration();
        verify(connection).close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testNotifyConnectionActive() {
        final BlockNode blockNode = mock(BlockNode.class);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        when(connection.configuration()).thenReturn(config);
        blockNodes().put(config.streamingEndpoint(), blockNode);

        connectionManager.notifyConnectionActive(connection);

        verify(connection).configuration();
        verify(blockNode).onActive(connection);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testNotifyConnectionClosed_untrackedNode() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        when(connection.configuration()).thenReturn(config);
        blockNodes().clear();

        connectionManager.notifyConnectionClosed(connection);

        verify(connection).configuration();
        verify(bufferService).notifyBlockNodeDisconnected(config.streamingEndpoint());
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testNotifyConnectionClosed_asNotActiveConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        when(connection.configuration()).thenReturn(config);
        blockNodes().clear();

        connectionManager.notifyConnectionClosed(connection);

        assertThat(activeStreamingConnectionRef()).hasValue(activeConnection);

        verify(connection).configuration();
        verify(bufferService).notifyBlockNodeDisconnected(config.streamingEndpoint());
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testNotifyConnectionClosed_observingConnection() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        when(node.configuration()).thenReturn(config);
        final BlockNodeObservingConnection observingConnection = mock(BlockNodeObservingConnection.class);
        when(observingConnection.blockNode()).thenReturn(node);
        when(observingConnection.configuration()).thenReturn(config);

        blockNodes().put(node.configuration().streamingEndpoint(), node);
        activeObservingConnections().put(node, observingConnection);

        connectionManager.notifyConnectionClosed(observingConnection);

        assertThat(activeObservingConnections()).isEmpty();

        verify(bufferService).notifyBlockNodeDisconnected(node.configuration().streamingEndpoint());
        verifyNoMoreInteractions(bufferService);
        verify(node).onClose(observingConnection);
        verifyNoMoreInteractions(node);
        verifyNoInteractions(metrics);
    }

    @Test
    void testNotifyConnectionClosed_asActiveConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNode blockNode = mock(BlockNode.class);
        when(activeConnection.configuration()).thenReturn(config);
        activeStreamingConnectionRef().set(activeConnection);
        blockNodes().put(config.streamingEndpoint(), blockNode);

        connectionManager.notifyConnectionClosed(activeConnection);

        assertThat(activeStreamingConnectionRef()).hasNullValue();

        verify(activeConnection).configuration();
        verify(blockNode).onClose(activeConnection);
        verify(bufferService).notifyBlockNodeDisconnected(config.streamingEndpoint());
        verifyNoMoreInteractions(activeConnection);
        verifyNoMoreInteractions(blockNode);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testFindAvailableNode_nullNodes() {
        assertThatThrownBy(() -> invoke_findAvailableStreamingNode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nodes must not be null");
    }

    @Test
    void testFindAvailableNode_emptyNodes() throws Throwable {
        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(List.of());
        assertThat(outcome).isNull();
    }

    @Test
    void testFindAvailableNode_fetchStatusInterrupted() throws Throwable {
        // throw an InterruptedException when the tasks are submitted
        doThrow(new InterruptedException())
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean isInterrupted = new AtomicBoolean();

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableStreamingNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                isInterrupted.set(Thread.currentThread().isInterrupted());
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();
        assertThat(isInterrupted).isTrue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_fetchStatusException() throws Throwable {
        // throw a RuntimeException when the tasks are submitted
        doThrow(new RuntimeException())
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableStreamingNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_mismatchedNumberOfTasks() throws Throwable {
        // one task is submitted, but we should return zero futures to enter the mismatch path
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of());
        final AtomicReference<GroupSelectionOutcome> outcomeRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final BlockNode blockNode = mock(BlockNode.class);
        when(blockNode.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final List<BlockNode> nodes = List.of(blockNode);
        final CountDownLatch latch = new CountDownLatch(1);

        Thread.ofVirtual().start(() -> {
            try {
                outcomeRef.set(invoke_findAvailableStreamingNode(nodes));
            } catch (final Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Throwable t = error.get();
        if (t != null) {
            throw t; // no exception should be thrown
        }

        assertThat(outcomeRef).hasNullValue();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(blockNode).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(blockNode);
    }

    @Test
    void testFindAvailableNode_noSuccessfulStatusChecks() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        final BlockNode node3 = mock(BlockNode.class);
        when(node3.configuration()).thenReturn(newBlockNodeConfig("localhost", 3456, 1));
        final BlockNode node4 = mock(BlockNode.class);
        when(node4.configuration()).thenReturn(newBlockNodeConfig("localhost", 4567, 1));
        // Node 1 future will be SUCCESS, but missing a value
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(null);
        // Node 2 future will be FAILED
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.FAILED);
        when(node2Future.exceptionNow()).thenReturn(new RuntimeException("Node2 is bad"));
        // Node 3 future will be CANCELLED
        final Future<Object> node3Future = mock(Future.class);
        when(node3Future.state()).thenReturn(State.CANCELLED);
        // Node 4 future will be RUNNING
        final Future<Object> node4Future = mock(Future.class);
        when(node4Future.state()).thenReturn(State.RUNNING);
        // the order of the nodes and futures should match
        final List<BlockNode> nodes = List.of(node1, node2, node3, node4);
        final List<Future<Object>> futures = List.of(node1Future, node2Future, node3Future, node4Future);
        when(blockingIoExecutor.invokeAll(anyList(), anyLong(), any(TimeUnit.class)))
                .thenReturn(futures);

        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(nodes);

        assertThat(outcome).isNull();

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).exceptionNow();
        verify(node3Future).state();
        verify(node3Future).cancel(true);
        verify(node4Future).state();
        verify(node4Future).cancel(true);
        verify(node1, times(3)).configuration();
        verify(node2, times(3)).configuration();
        verify(node3, times(3)).configuration();
        verify(node4, times(3)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node3).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node4).onServerStatusCheck(any(BlockNodeStatus.class));
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future, node3Future, node4Future);
        verifyNoMoreInteractions(node1, node2, node3, node4);
    }

    @Test
    void testFindAvailableNode_wantedOutOfRangeBlock() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        // return a status that indicates the block node wants block 6
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 5));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));

        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(List.of(node));

        assertThat(outcome).isNull();

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(nodeFuture).state();
        verify(nodeFuture).resultNow();
        verify(node, times(4)).configuration();
        verify(node).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node).applyCoolDown(any(BlockNodeOutOfRange.class));
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(nodeFuture);
        verifyNoMoreInteractions(node);
    }

    @Test
    void testFindAvailableNode_noBlocksBuffered() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        // set the block buffer to indicate there are no blocks created
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(-1L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(-1L);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 5));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));

        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(List.of(node));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates()).hasSize(1).contains(new NodeCandidate(node, 5));
        assertThat(outcome.lowestAheadCandidates()).isEmpty();
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(Long.MAX_VALUE);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(nodeFuture).state();
        verify(nodeFuture).resultNow();
        verify(node, times(4)).configuration();
        verify(node).onServerStatusCheck(any(BlockNodeStatus.class));
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(nodeFuture);
        verifyNoMoreInteractions(node);
    }

    @Test
    void testFindAvailableNode_inRangeCandidates() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        final BlockNode node3 = mock(BlockNode.class);
        when(node3.configuration()).thenReturn(newBlockNodeConfig("localhost", 4567, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 14));
        final Future<Object> node3Future = mock(Future.class);
        when(node3Future.state()).thenReturn(State.SUCCESS);
        when(node3Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, -100L));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future, node3Future));

        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(List.of(node1, node2, node3));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates())
                .hasSize(2)
                .contains(new NodeCandidate(node1, 12), new NodeCandidate(node2, 14));
        assertThat(outcome.lowestAheadCandidates()).isEmpty();
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(Long.MAX_VALUE);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node3Future).state();
        verify(node3Future).resultNow();
        verify(node1, times(4)).configuration();
        verify(node2, times(4)).configuration();
        verify(node3, times(4)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node3).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node3).applyCoolDown(any(BlockNodeOutOfRange.class));
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future, node3Future);
        verifyNoMoreInteractions(node1, node2, node3);
    }

    @Test
    void testFindAvailableNode_aheadCandidates() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 21));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 23));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final GroupSelectionOutcome outcome = invoke_findAvailableStreamingNode(List.of(node1, node2));

        assertThat(outcome).isNotNull();
        assertThat(outcome.inRangeCandidates()).isEmpty();
        assertThat(outcome.lowestAheadCandidates()).hasSize(1).contains(new NodeCandidate(node1, 21));
        assertThat(outcome.lowestAheadWantedBlock()).isEqualTo(21);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node1, times(4)).configuration();
        verify(node2, times(4)).configuration();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
    }

    @Test
    void testRetrieveBlockNodeStatusTask() {
        final BlockNodeServiceConnection svcConnection = mock(BlockNodeServiceConnection.class);
        final BlockNodeStatus status = new BlockNodeStatus(true, 10, 25);
        when(svcConnection.getBlockNodeStatus()).thenReturn(status);

        final RetrieveBlockNodeStatusTask task = new RetrieveBlockNodeStatusTask(svcConnection);
        final BlockNodeStatus actualStatus = task.call();

        assertThat(actualStatus).isNotNull().isEqualTo(status);

        verify(svcConnection).initialize();
        verify(svcConnection).getBlockNodeStatus();
        verify(svcConnection).close();
        verifyNoMoreInteractions(svcConnection);
    }

    @Test
    void testRetrieveBlockNodeStatusTask_closeCalledWhenInitializeThrows() {
        final BlockNodeServiceConnection svcConnection = mock(BlockNodeServiceConnection.class);
        final RuntimeException initError = new RuntimeException("initialization failed");
        doThrow(initError).when(svcConnection).initialize();

        final RetrieveBlockNodeStatusTask task = new RetrieveBlockNodeStatusTask(svcConnection);

        assertThatThrownBy(task::call).isInstanceOf(RuntimeException.class).hasMessage("initialization failed");

        // close() must be called even when initialize() throws to prevent resource leaks
        verify(svcConnection).initialize();
        verify(svcConnection).close();
        verifyNoMoreInteractions(svcConnection);
    }

    @Test
    void testGetNextPriorityBlockNode_skipP1GroupBecauseNoCandidates() throws Throwable {
        // Node 1 will have a priority of 1, but it will be unreachable
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.RUNNING); // this will mark the node as unreachable (timeout)
        // Node 2 will have a priority of 2, and it will be reachable
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 15));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(25L);
        // the blocking I/O executor will be called twice, once for Node 1 (priority 1 group) and once for Node 2
        // (priority 2 group)
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future), List.of(node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityStreamingBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2);

        verify(blockingIoExecutor, times(2)).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, times(4)).configuration();
        verify(node2, times(5)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node1Future).state();
        verify(node1Future).cancel(true);
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(bufferService);
        verifyNoMoreInteractions(blockingIoExecutor);
    }

    @Test
    void testGetNextPriorityBlockNode_p1GroupInRange() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 14));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityStreamingBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isIn(node1, node2);

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(5)).configuration();
        verify(node2, times(5)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testGetNextPriorityBlockNode_p1GroupAhead() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 1));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 22));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 20));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future, node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityStreamingBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2); // node 2 has the lowest ahead block, so it is selected

        verify(blockingIoExecutor).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(5)).configuration();
        verify(node2, times(5)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testGetNextPriorityBlockNode_p2GroupLowerAhead() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        // Node 2 is in priority group 2, and it has a lower ahead block
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 22));
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 20));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future), List.of(node2Future));

        final BlockNode selectedNode = invoke_getNextPriorityStreamingBlockNode(List.of(node1, node2));

        assertThat(selectedNode).isNotNull().isEqualTo(node2); // node 2 has the lowest ahead block, so it is selected

        verify(blockingIoExecutor, times(2)).invokeAll(anyList(), anyLong(), any(TimeUnit.class));
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2Future).state();
        verify(node2Future).resultNow();
        verify(node1, times(5)).configuration();
        verify(node2, times(5)).configuration();
        verify(node1).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(node2).onServerStatusCheck(any(BlockNodeStatus.class));
        verify(bufferService, times(2)).getEarliestAvailableBlockNumber();
        verify(bufferService, times(2)).getLastBlockNumberProduced();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future, node2Future);
        verifyNoMoreInteractions(node1, node2);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testPruneNodes() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isRemovable()).thenReturn(true);
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isRemovable()).thenReturn(false);
        final BlockNode node3 = mock(BlockNode.class);
        when(node3.isRemovable()).thenReturn(false);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        blockNodes().put(new BlockNodeEndpoint("localhost", 3456), node3);

        invoke_pruneNodes();

        assertThat(blockNodes())
                .hasSize(2)
                .containsOnlyKeys(new BlockNodeEndpoint("localhost", 2345), new BlockNodeEndpoint("localhost", 3456));

        verify(node1).isRemovable();
        verify(node2).isRemovable();
        verify(node3).isRemovable();
        verifyNoMoreInteractions(node1, node2, node3);
    }

    @Test
    void testSelectNewBlockNode_globalCoolDownActiveWithoutForce() throws Throwable {
        // set the global cooldown in the future
        globalCoolDownTimestampRef().set(Instant.now().plusSeconds(10));
        activeStreamingConnectionRef().set(null);

        invoke_selectNewStreamingBlockNode(false, new AnyCriteria(), CloseReason.BUFFER_SATURATION, null);

        assertThat(activeStreamingConnectionRef()).hasNullValue();
    }

    @Test
    void testSelectNewBlockNode_globalCoolDownActiveWithForce() throws Throwable {
        // set the global cool down to be in the future
        final Instant existingGlobalCoolDown = Instant.now().plusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isStreamingCandidate()).thenReturn(true);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future));

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewStreamingBlockNode(true, new AnyCriteria(), CloseReason.BUFFER_SATURATION, null);

            // one connection should have been created
            assertThat(mockConnection.constructed()).hasSize(1);
        }

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue();
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isAfter(existingGlobalCoolDown);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node1, atLeast(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2, atLeastOnce()).isStreamingCandidate();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future);
    }

    @Test
    void testSelectNewBlockNode_noCandidates() throws Throwable {
        // set the cooldown in the past
        final Instant existingGlobalCoolDown = Instant.now().minusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        final BlockNode node1 = mock(BlockNode.class);
        lenient().when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(node1.isStreamingCandidate()).thenReturn(false);
        final BlockNode node2 = mock(BlockNode.class);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewStreamingBlockNode(true, new AnyCriteria(), CloseReason.BUFFER_SATURATION, null);

            // no connection should have been created
            assertThat(mockConnection.constructed()).isEmpty();
        }

        assertThat(activeStreamingConnectionRef()).hasNullValue();

        // global cool down should not be updated since there is no new connection
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isEqualTo(existingGlobalCoolDown);

        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node2, atLeastOnce()).isStreamingCandidate();
    }

    @Test
    void testSelectNewBlockNode_foundCandidateWithActiveConnection() throws Throwable {
        // set the global cool down to be in the past
        final Instant existingGlobalCoolDown = Instant.now().minusSeconds(10);
        globalCoolDownTimestampRef().set(existingGlobalCoolDown);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        final BlockNode node1 = mock(BlockNode.class);
        when(node1.isStreamingCandidate()).thenReturn(true);
        when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 2);
        when(node2.isStreamingCandidate()).thenReturn(false); // not a candidate
        lenient().when(node2.configuration()).thenReturn(node2Config);
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future));
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        lenient().when(node2Conn.configuration()).thenReturn(node2Config);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        activeStreamingConnectionRef().set(node2Conn); // set node 2 as the current active connection

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewStreamingBlockNode(true, new AnyCriteria(), CloseReason.BUFFER_SATURATION, node2Conn);

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);

        // since a new connection has been created, the global cool down should be updated
        final Instant newGlobalCoolDownTimestamp = globalCoolDownTimestampRef().get();
        assertThat(newGlobalCoolDownTimestamp).isNotNull().isAfter(existingGlobalCoolDown);

        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(node2Conn).closeAtBlockBoundary(CloseReason.BUFFER_SATURATION);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node1, atLeast(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2, atLeastOnce()).isStreamingCandidate();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future);
    }

    @Test
    void testSelectNewBlockNode_initializeFailureFailsOverToLowerPriorityNode() throws Throwable {
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        // Node 1 is the highest priority; its status check passes but its stream can never be opened
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNode node1 = new BlockNode(
                configProvider, node1Config, new AtomicInteger(), new AtomicInteger(), new BlockNodeStats());
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        // Node 2 is a healthy, lower priority node
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNode node2 = new BlockNode(
                configProvider, node2Config, new AtomicInteger(), new AtomicInteger(), new BlockNodeStats());
        final Future<Object> node2Future = mock(Future.class);
        when(node2Future.state()).thenReturn(State.SUCCESS);
        when(node2Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        // the first selection only checks the priority 1 group; the second only has Node 2 as a candidate
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future), List.of(node2Future));

        blockNodes().put(node1Config.streamingEndpoint(), node1);
        blockNodes().put(node2Config.streamingEndpoint(), node2);

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class, (connection, context) -> {
                    final BlockNode selectedNode =
                            (BlockNode) context.arguments().get(1);
                    final BlockNodeConfiguration config = selectedNode.configuration();
                    lenient().when(connection.configuration()).thenReturn(config);
                    lenient()
                            .when(connection.connectionId())
                            .thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, context.getCount()));
                    if (selectedNode == node1) {
                        doThrow(new RuntimeException("Pipeline creation timed out"))
                                .when(connection)
                                .initialize();
                        // mirror the real close(): record the reason and notify the manager
                        doAnswer(invocation -> {
                                    when(connection.closeReason()).thenReturn(invocation.getArgument(0));
                                    connectionManager.notifyConnectionClosed(connection);
                                    return null;
                                })
                                .when(connection)
                                .close(any(CloseReason.class), any(Boolean.class), any());
                    }
                })) {
            // first attempt: Node 1 is selected, but its connection fails to initialize
            invoke_selectNewBlockNode(true, new AnyCriteria(), CloseReason.UNKNOWN, null);

            assertThat(mockConnection.constructed()).hasSize(1);
            assertThat(activeConnectionRef()).hasNullValue();
            assertThat(node1.isStreamingCandidate()).isFalse();
            assertThat(node2.isStreamingCandidate()).isTrue();

            // second attempt: Node 1 is cooling down, so the lower priority Node 2 is selected
            invoke_selectNewBlockNode(true, new AnyCriteria(), CloseReason.UNKNOWN, null);

            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(2);
            final BlockNodeStreamingConnection node2Connection = createdConnections.get(1);
            assertThat(node2Connection.configuration()).isEqualTo(node2Config);
            assertThat(activeConnectionRef()).hasValue(node2Connection);
            verify(node2Connection).updateConnectionState(ConnectionState.ACTIVE);
        }

        verify(blockingIoExecutor, times(2)).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testSelectNewBockNode_higherPriorityRequiredAndAvailable() throws Throwable {
        // create multiple block nodes with different priorities (1-3) but have the active connection be priority 2
        // this should trigger a node reselection where only nodes with higher priorities (i.e. priority 1) are selected
        final BlockNode node1 = mock(BlockNode.class);
        lenient().when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(node1.isStreamingCandidate()).thenReturn(true);
        final Future<Object> node1Future = mock(Future.class);
        when(node1Future.state()).thenReturn(State.SUCCESS);
        when(node1Future.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 2);
        lenient().when(node2.configuration()).thenReturn(node2Config);
        when(node2.isStreamingCandidate()).thenReturn(false); // it is the active connection already
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNode node3 = mock(BlockNode.class);
        lenient().when(node3.configuration()).thenReturn(newBlockNodeConfig("localhost", 3456, 3));
        when(node3.isStreamingCandidate()).thenReturn(true);
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(node1Future));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        blockNodes().put(new BlockNodeEndpoint("localhost", 3456), node3);
        activeStreamingConnectionRef().set(node2Conn); // set node 2 as the current active connection

        final BlockNodeStreamingConnection newActiveConnection;
        final List<Object> newConnectionConstructorArgs = new ArrayList<>();
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection = mockConstruction(
                BlockNodeStreamingConnection.class,
                (mock, ctx) -> newConnectionConstructorArgs.addAll(ctx.arguments()))) {
            invoke_selectNewStreamingBlockNode(
                    true, new MinimumPriorityCriteria(1), CloseReason.HIGHER_PRIORITY_FOUND, node2Conn);

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        // the second constructor argument is the BlockNode associated with the connection
        assertThat(newConnectionConstructorArgs).hasSize(10);
        final BlockNode newConnectionNode = (BlockNode) newConnectionConstructorArgs.get(1);
        assertThat(newConnectionNode).isEqualTo(node1);

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);

        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(node2Conn).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1, atLeastOnce()).isStreamingCandidate();
        verify(node1, atLeast(3)).configuration();
        verify(node1Future).state();
        verify(node1Future).resultNow();
        verify(node2, atLeastOnce()).isStreamingCandidate();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(node1Future);
    }

    @Test
    void testSelectNewBockNode_higherPriorityRequiredAndNotAvailable() throws Throwable {
        // create multiple block nodes with different priorities (1-3) but have the active connection be priority 2
        // this should trigger a node reselection where only nodes with higher priorities (i.e. priority 1) are selected
        // but mark the higher priority node as not streamable (e.g. due to previous failure)
        final BlockNode node1 = mock(BlockNode.class);
        lenient().when(node1.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(node1.isStreamingCandidate()).thenReturn(false);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 2);
        lenient().when(node2.configuration()).thenReturn(node2Config);
        when(node2.isStreamingCandidate()).thenReturn(false); // it is the active connection already
        final BlockNodeStreamingConnection node2Conn = mock(BlockNodeStreamingConnection.class);
        final BlockNode node3 = mock(BlockNode.class);
        lenient().when(node3.configuration()).thenReturn(newBlockNodeConfig("localhost", 3456, 3));
        when(node3.isStreamingCandidate()).thenReturn(true);

        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node1);
        blockNodes().put(new BlockNodeEndpoint("localhost", 2345), node2);
        blockNodes().put(new BlockNodeEndpoint("localhost", 3456), node3);
        activeStreamingConnectionRef().set(node2Conn); // set node 2 as the current active connection

        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_selectNewStreamingBlockNode(
                    true, new MinimumPriorityCriteria(1), CloseReason.HIGHER_PRIORITY_FOUND, node2Conn);

            // no connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).isEmpty();
        }

        assertThat(activeStreamingConnectionRef()).hasValue(node2Conn); // should be unchanged

        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testSelectNewBlockNode_wantedBlockFromServerStatus() throws Throwable {
        activeStreamingConnectionRef().set(null);
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet config =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(config);
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true);
        when(node.wantedBlock()).thenReturn(13L);
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);
        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        final BlockNodeStreamingConnection newActiveConnection;
        final List<Object> newConnectionConstructorArgs = new ArrayList<>();
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection = mockConstruction(
                BlockNodeStreamingConnection.class, (_, ctx) -> newConnectionConstructorArgs.addAll(ctx.arguments()))) {
            invoke_selectNewStreamingBlockNode(true, new AnyCriteria(), CloseReason.CONFIG_UPDATE, null);

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);
            newActiveConnection = createdConnections.getFirst();
        }

        // the seventh constructor argument is the block number to initialize the stream with
        assertThat(newConnectionConstructorArgs).hasSize(10);
        final Long initialBlockToStream = (Long) newConnectionConstructorArgs.get(6);
        // The block node server status API indicated that the last block available was 12, thus the next 'wanted' block
        // is 13 and that is what the connection should be initialized to start streaming
        assertThat(initialBlockToStream).isEqualTo(13L);

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);

        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node, atLeastOnce()).isStreamingCandidate();
        verify(node, atLeast(3)).configuration();
        verify(nodeFuture).state();
        verify(nodeFuture).resultNow();
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(nodeFuture);
    }

    @Test
    void testUpdateConnectionIfNeeded_noActiveConnectionOnly() throws Throwable {
        // set no active connection
        activeStreamingConnectionRef().set(null);
        // apply latest configuration
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet config =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(config);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(config);
        // indicate the buffer is healthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));
        // higher priority connection, stalled connection, and auto reset checks will be false since no active
        // connection exists

        // the following setup is to allow for the following node to be selected as the new active connection
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

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_recordsActiveConnectionIpWhenConnectionActive() throws Throwable {
        // a healthy active connection exists with a known IP address (192.168.1.1 -> 3232235777)
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.configuration()).thenReturn(newBlockNodeConfig("localhost", 1234, 1));
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        when(activeConnection.ipV4AddressAsInt()).thenReturn(3232235777L);
        activeStreamingConnectionRef().set(activeConnection);
        // healthy buffer and no configuration changes so the active connection is left in place
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));

        invoke_updateConnectionIfNeeded();

        // the gauge should report the active connection's IP address
        verify(metrics).recordActiveConnectionIp(3232235777L);
    }

    @Test
    void testUpdateConnectionIfNeeded_recordsZeroIpWhenNoActiveConnection() throws Throwable {
        // no active connection exists
        activeStreamingConnectionRef().set(null);
        // healthy buffer and no configured block nodes so no new connection is selected
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));

        invoke_updateConnectionIfNeeded();

        // with no active connection the gauge must transition to 0 rather than retaining a stale IP
        verify(metrics).recordActiveConnectionIp(0L);
    }

    @Test
    void testUpdateConnectionIfNeeded_recordsZeroIpAfterAllConfigRemoved() throws Throwable {
        // an active connection is streaming to a configured block node (192.168.1.1 -> 3232235777)
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        when(activeConnection.ipV4AddressAsInt()).thenReturn(3232235777L);
        activeStreamingConnectionRef().set(activeConnection);
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(false);
        // terminating the node ultimately closes its connection, which in production calls back into the
        // manager via notifyConnectionClosed; drive that real callback so the active connection reference is
        // cleared by production code rather than by the test poking the field directly
        doAnswer(inv -> {
                    connectionManager.notifyConnectionClosed(activeConnection);
                    return null;
                })
                .when(node)
                .onTerminate(CloseReason.CONFIG_UPDATE);
        blockNodes().put(nodeConfig.streamingEndpoint(), node);
        // the active configuration currently has a single block node
        activeConfigRef().set(new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig)));
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));
        // the whole block-nodes.json is removed -> the config service publishes an empty, version-bumped set
        when(blockNodeConfigService.latestConfiguration())
                .thenReturn(new VersionedBlockNodeConfigurationSet(2, List.of()));

        // tick 1: the removal is detected and the node terminates its connection; the resulting (real)
        // notifyConnectionClosed callback clears the active connection reference. The connection was still
        // active at the start of the tick, so the gauge reports its IP address.
        invoke_updateConnectionIfNeeded();
        verify(node).onTerminate(CloseReason.CONFIG_UPDATE);
        verify(metrics).recordActiveConnectionIp(3232235777L);
        // the active connection reference was cleared by production code, not by the test
        assertThat(activeStreamingConnectionRef()).hasNullValue();

        // tick 2: with no active connection remaining, the gauge transitions to 0 instead of keeping the old IP
        invoke_updateConnectionIfNeeded();
        verify(metrics).recordActiveConnectionIp(0L);
    }

    @Test
    void testUpdateConnectionIfNeeded_recordsZeroIpWhenNoBlockNodeConnectable() throws Throwable {
        // an active connection is streaming to a configured block node (192.168.1.1 -> 3232235777)
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        when(activeConnection.ipV4AddressAsInt()).thenReturn(3232235777L);
        activeStreamingConnectionRef().set(activeConnection);
        // the block node stays in configuration, but it cannot be connected to (e.g. unreachable / in cool-down)
        final BlockNode node = mock(BlockNode.class);
        lenient().when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(false);
        blockNodes().put(nodeConfig.streamingEndpoint(), node);
        // the block node configuration is unchanged - the node is simply unreachable
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));

        // tick 1: the connection is still active and healthy, so the gauge reports its IP address
        invoke_updateConnectionIfNeeded();
        verify(metrics).recordActiveConnectionIp(3232235777L);

        // the active connection is lost (e.g. the block node became unreachable); production clears the active ref
        connectionManager.notifyConnectionClosed(activeConnection);
        assertThat(activeStreamingConnectionRef()).hasNullValue();

        // tick 2: a reconnection is attempted, but no block node is connectable, so no replacement connection is
        // established and the gauge transitions to 0 instead of retaining the old IP
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();
            assertThat(mockConnection.constructed()).isEmpty();
        }

        verify(node, atLeastOnce()).isStreamingCandidate();
        assertThat(activeStreamingConnectionRef()).hasNullValue();
        verify(metrics).recordActiveConnectionIp(0L);
    }

    @Test
    void testUpdateConnectionIfNeeded_observersUpdatedOnly() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 2345, 1);
        final BlockNodeStreamingConnection activeStreamingConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeStreamingConnStats = mock(StreamingConnectionStatistics.class);
        when(activeStreamingConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeStreamingConnection.connectionStatistics()).thenReturn(activeStreamingConnStats);
        when(activeStreamingConnection.configuration()).thenReturn(node1Config);
        when(activeStreamingConnection.autoResetTimestamp())
                .thenReturn(Instant.now().plusSeconds(90));
        when(node2.configuration()).thenReturn(node2Config);
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(node1Config, node2Config));
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 10.0D, false));
        final BlockNodeObservingConnection activeObservingConnection = mock(BlockNodeObservingConnection.class);

        activeConfigRef().set(configSet);
        activeStreamingConnectionRef().set(activeStreamingConnection);
        activeObservingConnections().put(node2, activeObservingConnection);
        blockNodes().put(node1Config.streamingEndpoint(), node1);
        blockNodes().put(node2Config.streamingEndpoint(), node2);

        invoke_updateConnectionIfNeeded();

        assertThat(activeObservingConnections()).isEmpty();

        verify(activeObservingConnection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);

        verifyNoMoreInteractions(activeObservingConnection);
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_updatedConfigOnly() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(false);
        activeStreamingConnectionRef().set(activeConnection);
        // include in a lower priority node for fun
        final BlockNodeConfiguration lowerPriorityConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNode lowerPriorityNode = mock(BlockNode.class);
        when(lowerPriorityNode.configuration()).thenReturn(lowerPriorityConfig);
        when(lowerPriorityNode.isStreamingCandidate()).thenReturn(false);
        // set the current configuration
        final VersionedBlockNodeConfigurationSet currentConfigSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig, lowerPriorityConfig));
        activeConfigRef().set(currentConfigSet);
        // mark buffer healthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));
        // create new config that removes everything
        when(blockNodeConfigService.latestConfiguration())
                .thenReturn(new VersionedBlockNodeConfigurationSet(2, List.of()));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);
        blockNodes().put(lowerPriorityConfig.streamingEndpoint(), lowerPriorityNode);

        invoke_updateConnectionIfNeeded();

        verify(node).onTerminate(CloseReason.CONFIG_UPDATE);
        verify(lowerPriorityNode).onTerminate(CloseReason.CONFIG_UPDATE);
        // in the real world, when BlockNode#onTerminate(CloseReason) is called, it will close the connection
        // associated with the node, but since this is using mocks, those side effects aren't verifiable here
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_bufferUnhealthyOnly() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        lenient().when(node.configuration()).thenReturn(newBlockNodeConfig("localhost", 2345, 2));
        when(node.isStreamingCandidate()).thenReturn(false);
        activeStreamingConnectionRef().set(activeConnection);
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // mark the buffer as unhealthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 80.0D, true));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        invoke_updateConnectionIfNeeded();

        // because there is only one block node, even though we are experiencing an unhealthy buffer we will not close
        // the active connection - having some sort of connection is better than nothing in this scenario
        verify(activeConnection, never()).closeAtBlockBoundary(any(CloseReason.class));
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_higherPriorityFoundOnly() throws Throwable {
        // set lower priority active connection
        final BlockNodeConfiguration lowerPriorityConfig = newBlockNodeConfig("localhost", 2345, 2);
        final BlockNodeStreamingConnection lowerPriorityConnection = mock(BlockNodeStreamingConnection.class);
        when(lowerPriorityConnection.configuration()).thenReturn(lowerPriorityConfig);
        // set the heartbeat to be now to pass the stall check
        final StreamingConnectionStatistics lowerPriorityConnStats = mock(StreamingConnectionStatistics.class);
        when(lowerPriorityConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(lowerPriorityConnection.connectionStatistics()).thenReturn(lowerPriorityConnStats);
        // set the auto reset to be in the future to pass the auto reset check
        when(lowerPriorityConnection.autoResetTimestamp())
                .thenReturn(Instant.now().plusSeconds(90));
        final BlockNode lowerPriorityNode = mock(BlockNode.class);
        when(lowerPriorityNode.configuration()).thenReturn(lowerPriorityConfig);
        when(lowerPriorityNode.isStreamingCandidate()).thenReturn(false);
        activeStreamingConnectionRef().set(lowerPriorityConnection);
        // apply latest configuration
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final VersionedBlockNodeConfigurationSet config =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(config);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(config);
        // indicate the buffer is healthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));

        // the following setup is to allow for the following node to be selected as the new active connection
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
        blockNodes().put(lowerPriorityNode.configuration().streamingEndpoint(), lowerPriorityNode);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(lowerPriorityConnection).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_activeConnectionAutoResetOnly() throws Throwable {
        // set active connection that is ready for auto reset
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection existingActiveConnection = mock(BlockNodeStreamingConnection.class);
        when(existingActiveConnection.configuration()).thenReturn(nodeConfig);
        final StreamingConnectionStatistics existingConnStats = mock(StreamingConnectionStatistics.class);
        when(existingConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(1).toEpochMilli());
        when(existingActiveConnection.connectionStatistics()).thenReturn(existingConnStats);
        // set the auto reset timestamp to be in the past so it trigger the auto reset check
        when(existingActiveConnection.autoResetTimestamp())
                .thenReturn(Instant.now().minusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        when(node.configuration()).thenReturn(nodeConfig);
        when(node.isStreamingCandidate()).thenReturn(true); // an auto reset does not make a node non-streamable
        activeStreamingConnectionRef().set(existingActiveConnection);
        // mark the buffer as healthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));
        // we will attempt to re-connect to the same node, so make sure the setup accounts for this
        final Future<Object> nodeFuture = mock(Future.class);
        when(nodeFuture.state()).thenReturn(State.SUCCESS);
        when(nodeFuture.resultNow()).thenReturn(new BlockNodeStatus(true, 10, 12));
        when(blockingIoExecutor.invokeAll(anyCollection(), anyLong(), any(TimeUnit.class)))
                .thenReturn(List.of(nodeFuture));
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // other setup
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(10L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(20L);

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        final BlockNodeStreamingConnection newActiveConnection;
        try (final MockedConstruction<BlockNodeStreamingConnection> mockConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            invoke_updateConnectionIfNeeded();

            // one connection should have been created
            final List<BlockNodeStreamingConnection> createdConnections = mockConnection.constructed();
            assertThat(createdConnections).hasSize(1);

            newActiveConnection = createdConnections.getFirst();
        }

        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newActiveConnection);
        verify(newActiveConnection).initialize();
        verify(newActiveConnection).updateConnectionState(ConnectionState.ACTIVE);
        verify(existingActiveConnection, times(2)).closeAtBlockBoundary(CloseReason.PERIODIC_RESET);
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testUpdateConnectionIfNeeded_noUpdateNeeded() throws Throwable {
        // set active connection that is healthy
        final BlockNodeConfiguration nodeConfig = newBlockNodeConfig("localhost", 1234, 1);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(nodeConfig);
        final StreamingConnectionStatistics activeConnStats = mock(StreamingConnectionStatistics.class);
        when(activeConnStats.lastHeartbeatMillis())
                .thenReturn(Instant.now().plusSeconds(2).toEpochMilli());
        when(activeConnection.connectionStatistics()).thenReturn(activeConnStats);
        when(activeConnection.autoResetTimestamp()).thenReturn(Instant.now().plusSeconds(90));
        final BlockNode node = mock(BlockNode.class);
        activeStreamingConnectionRef().set(activeConnection);
        // set the latest config as the current config
        final VersionedBlockNodeConfigurationSet configSet =
                new VersionedBlockNodeConfigurationSet(1, List.of(nodeConfig));
        activeConfigRef().set(configSet);
        when(blockNodeConfigService.latestConfiguration()).thenReturn(configSet);
        // mark the buffer as unhealthy
        when(bufferService.latestBufferSaturationInfo()).thenReturn(buildSaturationInfo(Instant.now(), 0.0D, false));

        blockNodes().put(nodeConfig.streamingEndpoint(), node);

        invoke_updateConnectionIfNeeded();

        // the active connection should not be changed
        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(activeConnection);
        verify(metrics).recordActiveStreamingConnectionCount(anyLong());
        verify(metrics).recordActiveObservingConnectionCount(anyLong());
        verify(metrics).recordActiveConnectionIp(anyLong());
    }

    @Test
    void testStart_streamingNotEnabled() {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockStreamConfig bsConfig = mock(BlockStreamConfig.class);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(bsConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bsConfig.streamToBlockNodes()).thenReturn(false);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);
        connectionManager.start();

        assertThat(isConnectionManagerActive()).isFalse();
        assertThat(connectionMonitorThreadRef()).hasNullValue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockNodeConfigService);
    }

    @Test
    void testStart_alreadyStarted() {
        isConnectionManagerActive().set(true);

        connectionManager.start();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockNodeConfigService);
    }

    @Test
    void testStart_monitorThreadAlreadyExists() {
        final Thread monitorThread = mock(Thread.class);
        connectionMonitorThreadRef().set(monitorThread);

        connectionManager.start();

        assertThat(isConnectionManagerActive()).isTrue();
        assertThat(connectionMonitorThreadRef()).doesNotHaveNullValue().hasValue(monitorThread);

        verify(bufferService).start();
        verify(blockNodeConfigService).start();
    }

    @Test
    void testStart() {
        connectionManager.start();

        assertThat(isConnectionManagerActive()).isTrue();
        assertThat(connectionMonitorThreadRef()).doesNotHaveNullValue();

        verify(bufferService).start();
        verify(blockNodeConfigService).start();
    }

    @Test
    void hasActiveStreamingConnectionIsFalseWhenManagerInactive() {
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(connectionManager.hasActiveStreamingConnection()).isFalse();

        verifyNoInteractions(bufferService, metrics, blockNodeConfigService);
    }

    @Test
    void hasActiveStreamingConnectionIsFalseWhenNoActiveConnection() {
        isConnectionManagerActive().set(true);

        assertThat(connectionManager.hasActiveStreamingConnection()).isFalse();

        verifyNoInteractions(bufferService, metrics, blockNodeConfigService);
    }

    @Test
    void hasActiveStreamingConnectionIsTrueWhenManagerActiveWithConnection() {
        isConnectionManagerActive().set(true);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(connectionManager.hasActiveStreamingConnection()).isTrue();

        verifyNoInteractions(bufferService, metrics, blockNodeConfigService);
    }

    @Test
    void testShutdown_alreadyShutdown() {
        isConnectionManagerActive().set(false);

        connectionManager.shutdown();

        verify(bufferService, never()).shutdown();
        verify(blockNodeConfigService, never()).shutdown();
    }

    @Test
    void testShutdown() {
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(connection);
        isConnectionManagerActive().set(true);
        final Thread monitorThread = mock(Thread.class);
        connectionMonitorThreadRef().set(monitorThread);
        final BlockNode node = mock(BlockNode.class);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), node);

        connectionManager.shutdown();

        assertThat(activeStreamingConnectionRef()).hasNullValue();
        assertThat(isConnectionManagerActive()).isFalse();
        assertThat(connectionMonitorThreadRef()).hasNullValue();

        verify(node).onTerminate(CloseReason.SHUTDOWN);
        verify(bufferService).shutdown();
        verify(blockNodeConfigService).shutdown();
    }

    @Test
    void testConnectionMonitor_failedCheckStillBacksOff() {
        final long intervalNanos = TimeUnit.MILLISECONDS.toNanos(configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .connectionMonitorCheckIntervalMillis());
        final List<Long> checkTimes = new ArrayList<>();
        // every connectivity check fails; the second also stops the manager so the loop exits
        doAnswer(invocation -> {
                    checkTimes.add(System.nanoTime());
                    if (checkTimes.size() == 2) {
                        isConnectionManagerActive().set(false);
                    }
                    throw new IllegalStateException("connectivity check failed");
                })
                .when(metrics)
                .recordActiveStreamingConnectionCount(anyLong());
        isConnectionManagerActive().set(true);

        connectionManager.new ConnectionMonitorTask().run();

        // a failed check must still wait out the interval, not retry at once and spin
        assertThat(checkTimes).hasSize(2);
        assertThat(checkTimes.get(1) - checkTimes.get(0)).isGreaterThanOrEqualTo(intervalNanos);
    }

    @Test
    void testFetchBlockNodeStatus_nullCandidates() {
        assertThatThrownBy(() -> invoke_fetchBlockNodeStatus(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nodes must not be null");
    }

    @Test
    void testFetchBlockNodeStatus_emptyCandidates() throws Throwable {
        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of());

        assertThat(results).isNotNull().isEmpty();
    }

    @Test
    void testFetchBlockNodeStatus_executorInterrupted() throws Throwable {
        doAnswer(_ -> {
                    throw new InterruptedException();
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final AtomicBoolean isInterrupted = new AtomicBoolean(false);
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Map<BlockNode, BlockNodeStatus>> resultsRef = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                resultsRef.set(invoke_fetchBlockNodeStatus(List.of(node)));
            } catch (final Throwable t) {
                errorRef.set(t);
            } finally {
                isInterrupted.set(Thread.interrupted());
                latch.countDown();
            }
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef).hasNullValue();
        assertThat(isInterrupted).isTrue();

        final Map<BlockNode, BlockNodeStatus> results = resultsRef.get();
        assertThat(results).isNotNull().isEmpty();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_executorException() throws Throwable {
        doAnswer(_ -> {
                    throw new RuntimeException();
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node));
        assertThat(results).isNotNull().isEmpty();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_mismatchExecutorFuturesCount() throws Throwable {
        doAnswer(inv -> {
                    final Collection<Runnable> tasks = inv.getArgument(0);
                    final List<Future<BlockNodeStatus>> futures = new ArrayList<>();
                    for (int i = 1; i < tasks.size(); ++i) {
                        futures.add(CompletableFuture.completedFuture(new BlockNodeStatus(true, 10, 10)));
                    }

                    return futures;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node1, node2));

        assertThat(results).isNotNull().isEmpty();

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_successWithResult() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        doAnswer(_ -> {
                    final CompletableFuture<BlockNodeStatus> future = mock(CompletableFuture.class);
                    when(future.state()).thenReturn(Future.State.SUCCESS);
                    when(future.resultNow()).thenReturn(BlockNodeStatus.reachable(10, 100));

                    return List.of(future);
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node));

        assertThat(results).isNotNull().hasSize(1).containsEntry(node, BlockNodeStatus.reachable(10, 100));

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node).onServerStatusCheck(BlockNodeStatus.reachable(10, 100));
        verify(node, times(2)).configuration();

        verifyNoMoreInteractions(node);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_successWithoutResult() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        doAnswer(_ -> {
                    final CompletableFuture<BlockNodeStatus> future = mock(CompletableFuture.class);
                    when(future.state()).thenReturn(Future.State.SUCCESS);
                    when(future.resultNow()).thenReturn(null);

                    return List.of(future);
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node));

        assertThat(results).isNotNull().hasSize(1).containsEntry(node, BlockNodeStatus.notReachable());

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node).onServerStatusCheck(BlockNodeStatus.notReachable());
        verify(node, times(2)).configuration();

        verifyNoMoreInteractions(node);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_failed() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        doAnswer(_ -> {
                    // there are two nodes, fail them both but make one fail with a common error and the other without
                    final CompletableFuture<BlockNodeStatus> future1 = mock(CompletableFuture.class);
                    when(future1.state()).thenReturn(Future.State.FAILED);
                    when(future1.exceptionNow()).thenReturn(new ConnectException("connection refused"));

                    final CompletableFuture<BlockNodeStatus> future2 = mock(CompletableFuture.class);
                    when(future2.state()).thenReturn(Future.State.FAILED);
                    when(future2.exceptionNow()).thenReturn(new RuntimeException("foo"));

                    return List.of(future1, future2);
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node1, node2));

        assertThat(results)
                .isNotNull()
                .hasSize(2)
                .containsAllEntriesOf(
                        Map.of(node1, BlockNodeStatus.notReachable(), node2, BlockNodeStatus.notReachable()));

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(node1).onServerStatusCheck(BlockNodeStatus.notReachable());
        verify(node1, times(2)).configuration();
        verify(node2).onServerStatusCheck(BlockNodeStatus.notReachable());
        verify(node2, times(2)).configuration();

        verifyNoMoreInteractions(node1);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_cancelled() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final CompletableFuture<BlockNodeStatus> future = mock(CompletableFuture.class);
        when(future.state()).thenReturn(Future.State.CANCELLED);
        doAnswer(_ -> List.of(future))
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node));

        assertThat(results).isNotNull().hasSize(1).containsEntry(node, BlockNodeStatus.notReachable());

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(future).cancel(true);
        verify(node).onServerStatusCheck(BlockNodeStatus.notReachable());
        verify(node, times(2)).configuration();

        verifyNoMoreInteractions(node);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testFetchBlockNodeStatus_running() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final CompletableFuture<BlockNodeStatus> future = mock(CompletableFuture.class);
        when(future.state()).thenReturn(Future.State.RUNNING);
        doAnswer(_ -> List.of(future))
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        final Map<BlockNode, BlockNodeStatus> results = invoke_fetchBlockNodeStatus(List.of(node));

        assertThat(results).isNotNull().hasSize(1).containsEntry(node, BlockNodeStatus.notReachable());

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verify(future).cancel(true);
        verify(node).onServerStatusCheck(BlockNodeStatus.notReachable());
        verify(node, times(2)).configuration();

        verifyNoMoreInteractions(node);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testIsBlockNodeWithinRange_noBlocksProducedLocally() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean isWithinRange = invoke_isBlockNodeWithinRange(node, 100, -1, -1);

        assertThat(isWithinRange).isTrue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testIsBlockNodeWithinRange_outOfRangeNegative() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean isWithinRange = invoke_isBlockNodeWithinRange(node, -100, 0, 100);

        assertThat(isWithinRange).isFalse();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testIsBlockNodeWithinRange_behindNonNegative() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean isWithinRange = invoke_isBlockNodeWithinRange(node, 10, 50, 100);

        assertThat(isWithinRange).isFalse();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testIsBlockNodeWithinRange_inRangeAhead() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean isWithinRange = invoke_isBlockNodeWithinRange(node, 150, 50, 100);

        assertThat(isWithinRange).isTrue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testIsBlockNodeWithinRange_inRange() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean isWithinRange = invoke_isBlockNodeWithinRange(node, 75, 50, 100);

        assertThat(isWithinRange).isTrue();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSortBlockNodes_noResults() throws Throwable {
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(25L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(100L);

        final SortedBlockNodes sortedBlockNodes = invoke_sortBlockNodes(Map.of());

        assertThat(sortedBlockNodes).isNotNull();
        assertThat(sortedBlockNodes.rankedInRangeNodes()).isNotNull().isEmpty();
        assertThat(sortedBlockNodes.outOfRangeNodes()).isNotNull().isEmpty();
        assertThat(sortedBlockNodes.unreachableNodes()).isNotNull().isEmpty();

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();

        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testSortBlockNodes() throws Throwable {
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(25L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(100L);

        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNodeStatus node1Status = new BlockNodeStatus(true, 15, 100);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNodeStatus node2Status = new BlockNodeStatus(true, 3, 101);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        final BlockNodeStatus node3Status = new BlockNodeStatus(true, 10, 67);
        final BlockNode node4 = mockBlockNode("localhost", 8083, 1);
        final BlockNodeStatus node4Status = new BlockNodeStatus(false, -1, -1);
        final BlockNode node5 = mockBlockNode("localhost", 8084, 1);
        final BlockNodeStatus node5Status = new BlockNodeStatus(true, 8, -1);
        final BlockNode node6 = mockBlockNode("localhost", 8085, 1);
        final BlockNodeStatus node6Status = new BlockNodeStatus(true, 10, 10);
        final Map<BlockNode, BlockNodeStatus> input = Map.of(
                node1, node1Status,
                node2, node2Status,
                node3, node3Status,
                node4, node4Status,
                node5, node5Status,
                node6, node6Status);

        final SortedBlockNodes sortedBlockNodes = invoke_sortBlockNodes(input);

        assertThat(sortedBlockNodes).isNotNull();
        assertThat(sortedBlockNodes.rankedInRangeNodes())
                .isNotNull()
                .hasSize(4)
                .containsSequence(
                        new BlockNodeResult(node2, node2Status),
                        new BlockNodeResult(node5, node5Status),
                        new BlockNodeResult(node3, node3Status),
                        new BlockNodeResult(node1, node1Status));
        assertThat(sortedBlockNodes.outOfRangeNodes())
                .isNotNull()
                .hasSize(1)
                .contains(new BlockNodeResult(node6, node6Status));
        assertThat(sortedBlockNodes.unreachableNodes()).isNotNull().hasSize(1).contains(node4);

        verify(bufferService).getEarliestAvailableBlockNumber();
        verify(bufferService).getLastBlockNumberProduced();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(metrics);
    }

    @Test
    void testTryNewObservingConnection_initializeFailure() throws Throwable {
        doThrow(new RuntimeException("kaboom!")).when(blockingIoExecutor).submit(any(Runnable.class));
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final boolean successful = invoke_tryNewObservingConnection(node);

        assertThat(successful).isFalse();

        verify(metrics).recordConnectionCreateFailure(ConnectionType.ACK_OBSERVING);
        verify(metrics).recordConnectionClosed(ConnectionType.ACK_OBSERVING, CloseReason.INTERNAL_ERROR);
        verify(blockingIoExecutor).submit(any(Runnable.class));
        verify(blockingIoExecutor).shutdown();
        verify(blockingIoExecutor).awaitTermination(anyLong(), any(TimeUnit.class));
        verify(blockingIoExecutor).shutdownNow();
        verify(bufferService).notifyBlockNodeDisconnected(node.configuration().streamingEndpoint());
        verifyNoMoreInteractions(metrics);
        verifyNoMoreInteractions(blockingIoExecutor);
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testTryNewObservingConnection_withExistingConnection() throws Throwable {
        // this scenario should be impossible, but account for it anyway
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final BlockNodeObservingConnection existingObservingConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node, existingObservingConnection);

        try (final MockedConstruction<BlockNodeObservingConnection> obsConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final boolean success = invoke_tryNewObservingConnection(node);

            assertThat(success).isTrue();
            assertThat(obsConnection.constructed()).hasSize(1);
            final BlockNodeObservingConnection newConnection =
                    obsConnection.constructed().getFirst();
            assertThat(activeObservingConnections()).containsEntry(node, newConnection);

            verify(newConnection).initialize();
            verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
        }

        verify(existingObservingConnection).close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(existingObservingConnection);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testTryNewObservingConnection_withExistingConnectionCloseFailure() throws Throwable {
        // this scenario should be impossible, but account for it anyway
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final BlockNodeObservingConnection existingObservingConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node, existingObservingConnection);
        doThrow(new RuntimeException("catch me, if you can!"))
                .when(existingObservingConnection)
                .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));

        try (final MockedConstruction<BlockNodeObservingConnection> obsConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final boolean success = invoke_tryNewObservingConnection(node);

            assertThat(success).isTrue();
            assertThat(obsConnection.constructed()).hasSize(1);
            final BlockNodeObservingConnection newConnection =
                    obsConnection.constructed().getFirst();
            assertThat(activeObservingConnections()).containsEntry(node, newConnection);

            verify(newConnection).initialize();
            verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            verifyNoMoreInteractions(newConnection);
        }

        verify(existingObservingConnection).close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(existingObservingConnection);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testTryUpdateNewStreamingConnection_initializeFailure() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        try (final MockedConstruction<BlockNodeStreamingConnection> streamingConnection =
                mockConstruction(BlockNodeStreamingConnection.class, (mock, _) -> {
                    doThrow(new RuntimeException("gotcha!")).when(mock).initialize();
                    when(mock.connectionType()).thenReturn(ConnectionType.BLOCK_STREAMING);
                })) {
            final boolean success = invoke_tryUpdateNewStreamingConnection(node, CloseReason.CONFIG_UPDATE, null);

            assertThat(success).isFalse();
            assertThat(streamingConnection.constructed()).hasSize(1);
            final BlockNodeStreamingConnection newConnection =
                    streamingConnection.constructed().getFirst();

            verify(newConnection).initialize();
            verify(newConnection).close(CloseReason.INTERNAL_ERROR, true, null);
            verify(newConnection).connectionType();
            verify(metrics).recordConnectionCreateFailure(ConnectionType.BLOCK_STREAMING);
            verifyNoMoreInteractions(newConnection);
        }

        verifyNoMoreInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testTryUpdateNewStreamingConnection() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);
        final BlockNodeObservingConnection observingConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node, observingConnection);
        /*
        Note: We can't assert that the observing connection to the same node is removed from the active connection map
        because the removal occurs when the connection is closed and #notifyConnectionClosed is called. Since the
        connections are mocks, they aren't actually invoked. So we can only verify the close was called, but not that
        the map was cleaned up, at least not in this test.
         */
        final BlockNodeStreamingConnection activeStreamingConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(null);
        globalCoolDownTimestampRef().set(null);

        try (final MockedConstruction<BlockNodeStreamingConnection> streamingConnection =
                mockConstruction(BlockNodeStreamingConnection.class)) {
            final boolean success =
                    invoke_tryUpdateNewStreamingConnection(node, CloseReason.CONFIG_UPDATE, activeStreamingConnection);

            assertThat(success).isTrue();

            assertThat(streamingConnection.constructed()).hasSize(1);
            final BlockNodeStreamingConnection newConnection =
                    streamingConnection.constructed().getFirst();

            assertThat(activeStreamingConnectionRef()).hasValue(newConnection);
            assertThat(globalCoolDownTimestampRef()).doesNotHaveNullValue();

            verify(newConnection).initialize();
            verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            verifyNoMoreInteractions(newConnection);
        }

        verify(activeStreamingConnection).closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
        verifyNoMoreInteractions(activeStreamingConnection);
        verify(observingConnection).close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(observingConnection);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(blockingIoExecutor);
    }

    @Test
    void testMaybeUpdateObservingConnections_noObserversNeeded() throws Throwable {
        final BlockNode node = mockBlockNode("localhost", 8080, 1);

        final ObservingConnectionsUpdateResult updateResult = invoke_maybeUpdateObservingConnection(List.of(node), 0);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isFalse();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).isEmpty();

        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testMaybeUpdateObservingConnections_reuseActiveKnownObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        final BlockNode node4 = mockBlockNode("localhost", 8083, 1);
        // create a list of the nodes that acts as the ranked set of nodes (e.g. the first node is the highest rank)
        final List<BlockNode> rankedBlockNodes = List.of(node1, node2, node3, node4);
        // create enough observers to cover the minimum required
        final BlockNodeObservingConnection node1ObservingConnection = mock(BlockNodeObservingConnection.class);
        final BlockNodeObservingConnection node2ObservingConnection = mock(BlockNodeObservingConnection.class);
        final BlockNodeObservingConnection node3ObservingConnection = mock(BlockNodeObservingConnection.class);

        activeObservingConnections().put(node1, node1ObservingConnection);
        activeObservingConnections().put(node2, node2ObservingConnection);
        activeObservingConnections().put(node3, node3ObservingConnection);

        final ObservingConnectionsUpdateResult updateResult =
                invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isFalse();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).hasSize(3).contains(node1, node2, node3);
        assertThat(updateResult.prunedObservers()).isEmpty();

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_existingObserversKeptWhenManyCandidatesAvailable() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        final BlockNode node4 = mockBlockNode("localhost", 8083, 1);
        // node1 is already observed, so as in production it is not among the ranked candidates
        final List<BlockNode> rankedBlockNodes = List.of(node2, node3, node4);

        final BlockNodeObservingConnection node1ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node1ObservingConnection.isActive()).thenReturn(true);
        activeObservingConnections().put(node1, node1ObservingConnection);

        // 2 observers required in total, 1 exists, so only 1 new connection should be created
        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final ObservingConnectionsUpdateResult updateResult =
                    invoke_maybeUpdateObservingConnection(rankedBlockNodes, 2);

            assertThat(observingConnection.constructed()).hasSize(1);
            verify(node1ObservingConnection, never())
                    .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));
            assertThat(activeObservingConnections()).hasSize(2).containsKeys(node1, node2);
            assertThat(updateResult.newObservers()).hasSize(1).contains(node2);
            assertThat(updateResult.existingObservers()).hasSize(1).contains(node1);
            assertThat(updateResult.prunedObservers()).isEmpty();
        }

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_reuseActiveUnknownObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        // set the ranked list of nodes to be empty to simulate a failure in getting statuses for all the nodes
        final List<BlockNode> rankedBlockNodes = List.of();
        // create enough observers to cover the minimum required
        final BlockNodeObservingConnection node1ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node1ObservingConnection.isActive()).thenReturn(true);
        final BlockNodeObservingConnection node2ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node2ObservingConnection.isActive()).thenReturn(true);
        final BlockNodeObservingConnection node3ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node3ObservingConnection.isActive()).thenReturn(true);

        activeObservingConnections().put(node1, node1ObservingConnection);
        activeObservingConnections().put(node2, node2ObservingConnection);
        activeObservingConnections().put(node3, node3ObservingConnection);

        final ObservingConnectionsUpdateResult updateResult =
                invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isFalse();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).hasSize(3).contains(node1, node2, node3);
        assertThat(updateResult.prunedObservers()).isEmpty();

        verify(node1ObservingConnection).isActive();
        verifyNoMoreInteractions(node1ObservingConnection);
        verify(node2ObservingConnection).isActive();
        verifyNoMoreInteractions(node2ObservingConnection);
        verify(node3ObservingConnection).isActive();
        verifyNoMoreInteractions(node3ObservingConnection);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_newObserversCreatedWhileExistingObserversRemain() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);

        // node1 and node2 are already observed, so they are filtered out of the ranked candidates
        final List<BlockNode> rankedBlockNodes = List.of(node3);

        final BlockNodeObservingConnection node1ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node1ObservingConnection.isActive()).thenReturn(true);
        final BlockNodeObservingConnection node2ObservingConnection = mock(BlockNodeObservingConnection.class);
        when(node2ObservingConnection.isActive()).thenReturn(true);

        activeObservingConnections().put(node1, node1ObservingConnection);
        activeObservingConnections().put(node2, node2ObservingConnection);

        // 3 observers required in total, 2 already exist, so 1 additional observer is wanted
        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final ObservingConnectionsUpdateResult updateResult =
                    invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

            // the one new observer was created
            assertThat(observingConnection.constructed()).hasSize(1);

            // the existing observers must be left alone
            verify(node1ObservingConnection, never())
                    .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));
            verify(node2ObservingConnection, never())
                    .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));

            assertThat(activeObservingConnections()).hasSize(3).containsKeys(node1, node2, node3);
            assertThat(updateResult).isNotNull();
            assertThat(updateResult.updated()).isTrue();
            assertThat(updateResult.newObservers()).hasSize(1).contains(node3);
            assertThat(updateResult.existingObservers()).hasSize(2).contains(node1, node2);
            assertThat(updateResult.prunedObservers()).isEmpty();
        }

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_pruneUnneededObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        final BlockNode node4 = mockBlockNode("localhost", 8083, 1);
        // rank all the nodes
        final List<BlockNode> rankedBlockNodes = List.of(node1, node2, node3, node4);
        // create enough observers to cover the minimum required
        final BlockNodeObservingConnection node1ObservingConnection = mock(BlockNodeObservingConnection.class);
        final BlockNodeObservingConnection node2ObservingConnection = mock(BlockNodeObservingConnection.class);
        final BlockNodeObservingConnection node3ObservingConnection = mock(BlockNodeObservingConnection.class);
        final BlockNodeObservingConnection node4ObservingConnection = mock(BlockNodeObservingConnection.class);

        activeObservingConnections().put(node1, node1ObservingConnection);
        activeObservingConnections().put(node2, node2ObservingConnection);
        activeObservingConnections().put(node3, node3ObservingConnection);
        activeObservingConnections().put(node4, node4ObservingConnection);

        final ObservingConnectionsUpdateResult updateResult =
                invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isTrue();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).hasSize(3).contains(node1, node2, node3);
        assertThat(updateResult.prunedObservers()).hasSize(1).contains(node4);

        verify(node1ObservingConnection).isActive();
        verifyNoMoreInteractions(node1ObservingConnection);
        verify(node2ObservingConnection).isActive();
        verifyNoMoreInteractions(node2ObservingConnection);
        verify(node3ObservingConnection).isActive();
        verifyNoMoreInteractions(node3ObservingConnection);
        verify(node4ObservingConnection).isActive();
        verify(node4ObservingConnection).close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node4ObservingConnection);
        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_belowMinRequiredObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        // rank all the nodes
        final List<BlockNode> rankedBlockNodes = List.of(node1);

        // verify one observer is created
        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final ObservingConnectionsUpdateResult updateResult =
                    invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

            assertThat(observingConnection.constructed()).hasSize(1);
            for (final BlockNodeObservingConnection newConnection : observingConnection.constructed()) {
                verify(newConnection).initialize();
                verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            }

            assertThat(updateResult).isNotNull();
            assertThat(updateResult.updated()).isTrue();
            assertThat(updateResult.newObservers()).hasSize(1).contains(node1);
            assertThat(updateResult.existingObservers()).isEmpty();
            assertThat(updateResult.prunedObservers()).isEmpty();
        }

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_exactRequiredObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        // rank all the nodes
        final List<BlockNode> rankedBlockNodes = List.of(node1, node2);

        // verify one observer is created
        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final ObservingConnectionsUpdateResult updateResult =
                    invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

            assertThat(observingConnection.constructed()).hasSize(2);
            for (final BlockNodeObservingConnection newConnection : observingConnection.constructed()) {
                verify(newConnection).initialize();
                verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            }

            assertThat(updateResult).isNotNull();
            assertThat(updateResult.updated()).isTrue();
            assertThat(updateResult.newObservers()).hasSize(2).contains(node1, node2);
            assertThat(updateResult.existingObservers()).isEmpty();
            assertThat(updateResult.prunedObservers()).isEmpty();
        }

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testMaybeUpdateObservingConnections_idealObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNode node3 = mockBlockNode("localhost", 8082, 1);
        final BlockNode node4 = mockBlockNode("localhost", 8083, 1);
        // rank all the nodes
        final List<BlockNode> rankedBlockNodes = List.of(node1, node2, node3, node4);

        // verify one observer is created
        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final ObservingConnectionsUpdateResult updateResult =
                    invoke_maybeUpdateObservingConnection(rankedBlockNodes, 3);

            assertThat(observingConnection.constructed()).hasSize(3);
            for (final BlockNodeObservingConnection newConnection : observingConnection.constructed()) {
                verify(newConnection).initialize();
                verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            }

            assertThat(updateResult).isNotNull();
            assertThat(updateResult.updated()).isTrue();
            assertThat(updateResult.newObservers()).hasSize(3).contains(node1, node2, node3);
            assertThat(updateResult.existingObservers()).isEmpty();
            assertThat(updateResult.prunedObservers()).isEmpty();
        }

        verifyNoInteractions(metrics);
        verifyNoInteractions(bufferService);
    }

    @Test
    void testRefreshObservingConnections_minAcksOne() throws Throwable {
        // this test simulates when there is an active observer, but the min ACKs required is 1 which effectively
        // disables using additional observing connections
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 8080, 1);
        when(node1.configuration()).thenReturn(node1Config);
        final BlockNodeObservingConnection node1Connection = mock(BlockNodeObservingConnection.class);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(1);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Connection);

        final boolean updated = invoke_refreshObservingConnections();

        assertThat(updated).isTrue();
        // activeObservingConnections is called on close, but since we are using mocks
        // it doesn't get called so we can't verify here

        verify(node1).configuration();
        verifyNoMoreInteractions(node1);
        verify(node1Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node1Connection);
    }

    @Test
    void testRefreshObservingConnections_noNewObserversWanted() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeObservingConnection node1Connection = mock(BlockNodeObservingConnection.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeObservingConnection node2Connection = mock(BlockNodeObservingConnection.class);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Connection);
        activeObservingConnections().put(node2, node2Connection);

        final boolean updated = invoke_refreshObservingConnections();

        assertThat(updated).isFalse();
        assertThat(activeObservingConnections())
                .hasSize(2)
                .contains(Map.entry(node1, node1Connection), Map.entry(node2, node2Connection));

        verifyNoInteractions(node1);
        verifyNoInteractions(node1Connection);
    }

    @Test
    void testRefreshObservingConnections_tooManyObservers() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 8080, 1);
        final BlockNodeObservingConnection node1Connection = mock(BlockNodeObservingConnection.class);
        when(node1.configuration()).thenReturn(node1Config);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(1);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Connection);

        final boolean updated = invoke_refreshObservingConnections();

        assertThat(updated).isTrue();
        // the active observing connection map is updated when the connection is closed, but since we are testing with
        // mock connections, the map doesn't actually get updated

        verify(node1Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node1Connection);
        verify(node1).configuration();
        verifyNoMoreInteractions(node1);
    }

    @Test
    void testRefreshObservingConnection_notEnoughObservers() throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 8080, 1);
        when(node1.isObservingCandidate()).thenReturn(true);
        when(node1.configuration()).thenReturn(node1Config);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 8081, 1);
        when(node2.isObservingCandidate()).thenReturn(true);
        when(node2.configuration()).thenReturn(node2Config);
        final BlockNode node3 = mock(BlockNode.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig("localhost", 8082, 1);
        when(node3.isObservingCandidate()).thenReturn(true);
        when(node3.configuration()).thenReturn(node3Config);
        final BlockNode node4 = mock(BlockNode.class);
        final BlockNodeConfiguration node4Config = newBlockNodeConfig("localhost", 8083, 1);
        when(node4.isObservingCandidate()).thenReturn(true);
        when(node4.configuration()).thenReturn(node4Config);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        final BlockNodeConnectionConfig connectionConfig = mock(BlockNodeConnectionConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(connectionConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(3);
        when(connectionConfig.connectionManagementTimeout()).thenReturn(Duration.ofSeconds(2));
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(50L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(125L);

        doAnswer(inv -> {
                    /*
                    The ordering of the nodes is not fixed, so we can't simply return a list of futures in the order we
                    _think_ the application will to ensure the statuses align with our expectations. Therefore, we have
                    to do some less-than-ideal workarounds to ensure the correct status is returned for the correct
                    block node.
                     */
                    final Collection<RetrieveBlockNodeStatusTask> tasks = inv.getArgument(0);
                    final List<Future<BlockNodeStatus>> out = new ArrayList<>();

                    for (final RetrieveBlockNodeStatusTask task : tasks) {
                        final BlockNodeServiceConnection svcConnection =
                                (BlockNodeServiceConnection) retrieveBlockNodeStatusTask_svcConnectionHandle.get(task);
                        final BlockNodeEndpoint svcConnectionEndpoint =
                                svcConnection.configuration().streamingEndpoint();
                        // make node 1 have a higher latency than all the other nodes
                        if (node1Config.streamingEndpoint().equals(svcConnectionEndpoint)) {
                            out.add(CompletableFuture.completedFuture(BlockNodeStatus.reachable(100, 100)));
                        } else {
                            out.add(CompletableFuture.completedFuture(BlockNodeStatus.reachable(25, 100)));
                        }
                    }

                    return out;
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().clear();
        blockNodes().put(node1Config.streamingEndpoint(), node1);
        blockNodes().put(node2Config.streamingEndpoint(), node2);
        blockNodes().put(node3Config.streamingEndpoint(), node3);
        blockNodes().put(node4Config.streamingEndpoint(), node4);

        final Map<BlockNode, BlockNodeObservingConnection> connectionsCreated = new HashMap<>();

        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class, (conn, ctx) -> {
                    final BlockNode blockNode = (BlockNode) ctx.arguments().get(1);
                    connectionsCreated.put(blockNode, conn);
                })) {

            final boolean updated = invoke_refreshObservingConnections();
            assertThat(updated).isTrue();
            assertThat(observingConnection.constructed()).hasSize(3);

            for (final BlockNodeObservingConnection newConnection : observingConnection.constructed()) {
                verify(newConnection).initialize();
                verify(newConnection).updateConnectionState(ConnectionState.ACTIVE);
            }
        }

        // nodes 2, 3, and 4 should have been selected since they had the lowest latency
        assertThat(activeObservingConnections())
                .hasSize(3)
                .contains(
                        Map.entry(node2, connectionsCreated.get(node2)),
                        Map.entry(node3, connectionsCreated.get(node3)),
                        Map.entry(node4, connectionsCreated.get(node4)));
    }

    @Test
    void testRefreshObservingConnections_lessThanNumberOfIdealObserversButNotEnoughTimeSinceLastAttempt()
            throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 8080, 1);
        final BlockNodeStreamingConnection node1StrConnection = mock(BlockNodeStreamingConnection.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 8081, 1);
        final BlockNodeObservingConnection node2ObsConnection = mock(BlockNodeObservingConnection.class);
        final BlockNode node3 = mock(BlockNode.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig("localhost", 8082, 1);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        final BlockNodeConnectionConfig connectionConfig = mock(BlockNodeConnectionConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(connectionConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);
        when(connectionConfig.idealObserversCoolDownSeconds()).thenReturn(30);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node2ObsConnection);
        activeStreamingConnectionRef().set(node1StrConnection);
        blockNodes().put(node1Config.streamingEndpoint(), node1);
        blockNodes().put(node2Config.streamingEndpoint(), node2);
        blockNodes().put(node3Config.streamingEndpoint(), node3);

        // the cool down period is 30 seconds, so set the last attempt to be 5 seconds ago to skip this attempt
        final Instant lastAttemptTimestamp = Instant.now().minusSeconds(5);
        idealObserversLastAttemptTimestampRef().set(lastAttemptTimestamp);

        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final boolean updated = invoke_refreshObservingConnections();

            assertThat(updated).isFalse();
            assertThat(observingConnection.constructed()).isEmpty();
        }

        verifyNoInteractions(blockingIoExecutor);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testRefreshObservingConnections_lessThanNumberOfIdealObserversWithEnoughTimeSinceLastAttempt()
            throws Throwable {
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeConfiguration node1Config = newBlockNodeConfig("localhost", 8080, 1);
        final BlockNodeStreamingConnection node1StrConnection = mock(BlockNodeStreamingConnection.class);
        when(node1.isObservingCandidate()).thenReturn(false);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeConfiguration node2Config = newBlockNodeConfig("localhost", 8081, 1);
        final BlockNodeObservingConnection node2ObsConnection = mock(BlockNodeObservingConnection.class);
        when(node2.isObservingCandidate()).thenReturn(false);
        when(node2ObsConnection.isActive()).thenReturn(true);
        final BlockNode node3 = mock(BlockNode.class);
        final BlockNodeConfiguration node3Config = newBlockNodeConfig("localhost", 8082, 1);
        when(node3.isObservingCandidate()).thenReturn(true);
        when(node3.configuration()).thenReturn(node3Config);
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        final BlockNodeConnectionConfig connectionConfig = mock(BlockNodeConnectionConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(connectionConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);
        when(connectionConfig.connectionManagementTimeout()).thenReturn(Duration.ofSeconds(2));
        when(connectionConfig.idealObserversCoolDownSeconds()).thenReturn(30);
        when(bufferService.getEarliestAvailableBlockNumber()).thenReturn(50L);
        when(bufferService.getLastBlockNumberProduced()).thenReturn(125L);
        doAnswer(_ -> {
                    return List.of(CompletableFuture.completedFuture(BlockNodeStatus.reachable(10, 100)));
                })
                .when(blockingIoExecutor)
                .invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node2ObsConnection);
        activeStreamingConnectionRef().set(node1StrConnection);
        blockNodes().put(node1Config.streamingEndpoint(), node1);
        blockNodes().put(node2Config.streamingEndpoint(), node2);
        blockNodes().put(node3Config.streamingEndpoint(), node3);

        // the cool down period is 30 seconds, so set the last attempt to be 35 seconds ago to permit the attempt
        final Instant lastAttemptTimestamp = Instant.now().minusSeconds(35);
        idealObserversLastAttemptTimestampRef().set(lastAttemptTimestamp);

        try (final MockedConstruction<BlockNodeObservingConnection> observingConnection =
                mockConstruction(BlockNodeObservingConnection.class)) {
            final boolean updated = invoke_refreshObservingConnections();

            assertThat(updated).isTrue();
            assertThat(observingConnection.constructed()).hasSize(1);
        }

        assertThat(idealObserversLastAttemptTimestampRef())
                .doesNotHaveNullValue()
                .hasValueSatisfying(ts -> assertThat(ts).isAfter(lastAttemptTimestamp));
        assertThat(activeObservingConnections()).hasSize(2);

        verify(blockingIoExecutor).invokeAll(anyCollection(), anyLong(), any(TimeUnit.class));
        verifyNoMoreInteractions(blockingIoExecutor);
        verify(bufferService).getEarliestAvailableBlockNumber();
        verifyNoMoreInteractions(bufferService);
        verifyNoInteractions(metrics);
    }

    @Test
    void testPruneExcessObservers_lessThanOne() throws Throwable {
        final BlockNode node = mock(BlockNode.class);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node, connection);

        final ObservingConnectionsUpdateResult updateResult = invoke_pruneExcessObservers(-1);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isFalse();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).hasSize(1).contains(node);
        assertThat(updateResult.prunedObservers()).isEmpty();
        assertThat(activeObservingConnections()).hasSize(1).containsEntry(node, connection);

        verifyNoInteractions(node);
        verifyNoInteractions(connection);
    }

    @Test
    void testPruneExcessObservers_withCloseFailure() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNodeObservingConnection node1Connection = mock(BlockNodeObservingConnection.class);
        doThrow(new RuntimeException("foo"))
                .when(node1Connection)
                .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNodeObservingConnection node2Connection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node1, node1Connection);
        activeObservingConnections().put(node2, node2Connection);

        final ObservingConnectionsUpdateResult updateResult = invoke_pruneExcessObservers(2);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isTrue();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).isEmpty(); // all pruned
        assertThat(updateResult.prunedObservers()).hasSize(2).contains(node1, node2);
        assertThat(activeObservingConnections()).isEmpty();

        verify(node1Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node1Connection);
        verify(node2Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node2Connection);
        verify(node1).configuration();
        verifyNoMoreInteractions(node1);
        verify(node2).configuration();
        verifyNoMoreInteractions(node2);
    }

    @Test
    void testPruneExcessObservers() throws Throwable {
        final BlockNode node1 = mockBlockNode("localhost", 8080, 1);
        final BlockNodeObservingConnection node1Connection = mock(BlockNodeObservingConnection.class);
        final BlockNode node2 = mockBlockNode("localhost", 8081, 1);
        final BlockNodeObservingConnection node2Connection = mock(BlockNodeObservingConnection.class);
        activeObservingConnections().put(node1, node1Connection);
        activeObservingConnections().put(node2, node2Connection);

        final ObservingConnectionsUpdateResult updateResult = invoke_pruneExcessObservers(2);

        assertThat(updateResult).isNotNull();
        assertThat(updateResult.updated()).isTrue();
        assertThat(updateResult.newObservers()).isEmpty();
        assertThat(updateResult.existingObservers()).isEmpty(); // all pruned
        assertThat(updateResult.prunedObservers()).hasSize(2).contains(node1, node2);
        assertThat(activeObservingConnections()).isEmpty();

        verify(node1Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node1Connection);
        verify(node2Connection).close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(node2Connection);
        verify(node1).configuration();
        verifyNoMoreInteractions(node1);
        verify(node2).configuration();
        verifyNoMoreInteractions(node2);
    }

    @Test
    void testIsObservingUpdateNeeded_multiAckDisabled_noObservers() throws Throwable {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(1);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().clear();

        final boolean updateNeeded = invoke_isObservingUpdateNeeded();

        assertThat(updateNeeded).isFalse();
    }

    @Test
    void testIsObservingUpdateNeeded_multiAckDisabled_withObservers() throws Throwable {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(1);
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeObservingConnection node1Observer = mock(BlockNodeObservingConnection.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeObservingConnection node2Observer = mock(BlockNodeObservingConnection.class);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Observer);
        activeObservingConnections().put(node2, node2Observer);

        final boolean updateNeeded = invoke_isObservingUpdateNeeded();

        assertThat(updateNeeded).isTrue();
    }

    @Test
    void testIsObservingUpdateNeeded_multiAckEnabled_noObservers() throws Throwable {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().clear();

        final boolean updateNeeded = invoke_isObservingUpdateNeeded();

        assertThat(updateNeeded).isTrue();
    }

    @Test
    void testIsObservingUpdateNeeded_multiAckEnabled_tooManyObservers() throws Throwable {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeObservingConnection node1Observer = mock(BlockNodeObservingConnection.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeObservingConnection node2Observer = mock(BlockNodeObservingConnection.class);
        final BlockNode node3 = mock(BlockNode.class);
        final BlockNodeObservingConnection node3Observer = mock(BlockNodeObservingConnection.class);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Observer);
        activeObservingConnections().put(node2, node2Observer);
        activeObservingConnections().put(node3, node3Observer);

        final boolean updateNeeded = invoke_isObservingUpdateNeeded();

        assertThat(updateNeeded).isTrue();
    }

    @Test
    void testIsObservingUpdateNeeded_multiAckEnabled_exactCountOfObservers() throws Throwable {
        final ConfigProvider localConfigProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        when(versionedConfiguration.getConfigData(BlockBufferConfig.class)).thenReturn(bufferConfig);
        when(localConfigProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(bufferConfig.minAcksRequiredPerBlock()).thenReturn(2);
        final BlockNode node1 = mock(BlockNode.class);
        final BlockNodeObservingConnection node1Observer = mock(BlockNodeObservingConnection.class);
        final BlockNode node2 = mock(BlockNode.class);
        final BlockNodeObservingConnection node2Observer = mock(BlockNodeObservingConnection.class);

        connectionManager = new BlockNodeConnectionManager(
                localConfigProvider,
                bufferService,
                metrics,
                networkInfo,
                blockingIoExecutorSupplier,
                blockNodeConfigService,
                streamingObs);

        activeObservingConnections().put(node1, node1Observer);
        activeObservingConnections().put(node2, node2Observer);

        final boolean updateNeeded = invoke_isObservingUpdateNeeded();

        assertThat(updateNeeded).isFalse();
    }

    // Utilities

    @SuppressWarnings("unchecked")
    AtomicReference<Instant> idealObserversLastAttemptTimestampRef() {
        return (AtomicReference<Instant>) idealObserversLastAttemptTimestampRefHandle.get(connectionManager);
    }

    boolean invoke_isObservingUpdateNeeded() throws Throwable {
        return (boolean) isObservingUpdateNeededHandle.invoke(connectionManager);
    }

    ObservingConnectionsUpdateResult invoke_pruneExcessObservers(final int numToPrune) throws Throwable {
        return (ObservingConnectionsUpdateResult) pruneExcessObserversHandle.invoke(connectionManager, numToPrune);
    }

    boolean invoke_refreshObservingConnections() throws Throwable {
        return (boolean) refreshObservingConnectionsHandle.invoke(connectionManager);
    }

    @SuppressWarnings("unchecked")
    ConcurrentMap<BlockNode, BlockNodeObservingConnection> activeObservingConnections() {
        return (ConcurrentMap<BlockNode, BlockNodeObservingConnection>)
                activeObservingConnectionsHandle.get(connectionManager);
    }

    BlockNode mockBlockNode(final String host, final int port, final int priority) {
        final BlockNode node = mock(BlockNode.class);
        lenient().when(node.configuration()).thenReturn(newBlockNodeConfig(host, port, priority));

        return node;
    }

    ObservingConnectionsUpdateResult invoke_maybeUpdateObservingConnection(
            final List<BlockNode> rankedNodes, final int numObserversWanted) throws Throwable {
        return (ObservingConnectionsUpdateResult)
                maybeUpdateObservingConnectionsHandle.invoke(connectionManager, rankedNodes, numObserversWanted);
    }

    boolean invoke_tryUpdateNewStreamingConnection(
            final BlockNode streamingNode,
            final CloseReason closeReason,
            final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) tryUpdateNewStreamingConnectionHandle.invoke(
                connectionManager, streamingNode, closeReason, activeConnection);
    }

    boolean invoke_tryNewObservingConnection(final BlockNode node) throws Throwable {
        return (boolean) tryNewObservingConnectionHandle.invoke(connectionManager, node);
    }

    SortedBlockNodes invoke_sortBlockNodes(final Map<BlockNode, BlockNodeStatus> results) throws Throwable {
        return (SortedBlockNodes) sortBlockNodesHandle.invoke(connectionManager, results);
    }

    boolean invoke_isBlockNodeWithinRange(
            final BlockNode node,
            final long wantedBlock,
            final long earliestAvailableBlock,
            final long latestAvailableBlock)
            throws Throwable {
        return (boolean) isBlockNodeWithinRangeHandle.invoke(
                connectionManager, node, wantedBlock, earliestAvailableBlock, latestAvailableBlock);
    }

    @SuppressWarnings("unchecked")
    Map<BlockNode, BlockNodeStatus> invoke_fetchBlockNodeStatus(final List<BlockNode> nodes) throws Throwable {
        return (Map<BlockNode, BlockNodeStatus>) fetchBlockNodeStatusHandle.invoke(connectionManager, nodes);
    }

    BlockBufferSaturationInfo buildSaturationInfo(
            final Instant timestamp, final double saturationPercent, final boolean isAtActionStage) {
        final int count = (int) saturationPercent;
        return new BlockBufferSaturationInfo(
                timestamp,
                new SaturationDetail(saturationPercent, isAtActionStage, count, 100),
                new SaturationDetail(0.0D, false, 5, 100_000),
                new SaturationDetail(0.0D, false, 0, 5));
    }

    void invoke_updateConnectionIfNeeded() throws Throwable {
        updateConnectionIfNeededHandle.invoke(connectionManager);
    }

    void invoke_selectNewStreamingBlockNode(
            final boolean force,
            final NodeSelectionCriteria criteria,
            final CloseReason closeReason,
            final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        selectNewStreamingBlockNodeHandle.invoke(connectionManager, force, criteria, closeReason, activeConnection);
    }

    void invoke_pruneNodes() throws Throwable {
        pruneNodesHandle.invoke(connectionManager);
    }

    BlockNode invoke_getNextPriorityStreamingBlockNode(final List<BlockNode> availableBlockNodes) throws Throwable {
        return (BlockNode) getNextPriorityStreamingBlockNodeHandle.invoke(connectionManager, availableBlockNodes);
    }

    GroupSelectionOutcome invoke_findAvailableStreamingNode(final List<BlockNode> nodes) throws Throwable {
        return (GroupSelectionOutcome) findAvailableStreamingNodeHandle.invoke(connectionManager, nodes);
    }

    boolean invoke_isMissingActiveStreamingConnection(final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isMissingActiveStreamingConnectionHandle.invoke(connectionManager, activeConnection);
    }

    boolean invoke_isConfigUpdated() throws Throwable {
        return (boolean) isConfigUpdatedHandle.invoke(connectionManager);
    }

    boolean invoke_isBufferUnhealthy() throws Throwable {
        return (boolean) isBufferUnhealthyHandle.invoke(connectionManager);
    }

    boolean invoke_isHigherPriorityNodeAvailable(final BlockNodeStreamingConnection activeConnection) throws Throwable {
        return (boolean) isHigherPriorityNodeAvailableHandle.invoke(connectionManager, activeConnection);
    }

    boolean invoke_isActiveConnectionAutoReset(final Instant now, final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isActiveConnectionAutoResetHandle.invoke(connectionManager, now, activeConnection);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<Thread> connectionMonitorThreadRef() {
        return (AtomicReference<Thread>) connectionMonitorThreadRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<VersionedBlockNodeConfigurationSet> activeConfigRef() {
        return (AtomicReference<VersionedBlockNodeConfigurationSet>) activeConfigRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<Instant> globalCoolDownTimestampRef() {
        return (AtomicReference<Instant>) globalCoolDownTimestampRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockBufferSaturationInfo> bufferStatusRef() {
        return (AtomicReference<BlockBufferSaturationInfo>) bufferStatusRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockNodeStreamingConnection> activeStreamingConnectionRef() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    ConcurrentMap<BlockNodeEndpoint, BlockNode> blockNodes() {
        return (ConcurrentMap<BlockNodeEndpoint, BlockNode>) blockNodesHandle.get(connectionManager);
    }

    AtomicInteger globalActiveStreamingConnectionCount() {
        return (AtomicInteger) globalActiveStreamingConnectionCountHandle.get(connectionManager);
    }

    AtomicBoolean isConnectionManagerActive() {
        return (AtomicBoolean) isConnectionManagerActiveHandle.get(connectionManager);
    }

    void resetMocks() {
        reset(bufferService, metrics, blockNodeConfigService);
    }
}
