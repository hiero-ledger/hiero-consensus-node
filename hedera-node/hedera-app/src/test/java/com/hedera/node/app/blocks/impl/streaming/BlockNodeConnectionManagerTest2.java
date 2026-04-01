// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetrieveBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.metrics.BlockStreamMetrics;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConnectionManagerTest2 extends BlockNodeCommunicationTestBase {

    private static final VarHandle isManagerActiveHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle nodeStatusTaskConnectionHandle;
    private static final VarHandle blockNodesHandle;
    private static final VarHandle globalActiveStreamingConnectionCountHandle;
    private static final VarHandle activeConfigRefHandle;
    private static final VarHandle globalCoolDownTimestampRefHandle;
    private static final VarHandle bufferStatusRefHandle;

    private static final MethodHandle isActiveConnectionAutoResetHandle;
    private static final MethodHandle isActiveConnectionStalledHandle;
    private static final MethodHandle isHigherPriorityNodeAvailableHandle;
    private static final MethodHandle isBufferUnhealthyHandle;
    private static final MethodHandle isConfigUpdatedHandle;
    private static final MethodHandle isMissingActiveConnectionHandle;

    static {
        try {
            final Class<BlockNodeConnectionManager> cls = BlockNodeConnectionManager.class;
            final Lookup lookup =
                    MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, MethodHandles.lookup());
            isManagerActiveHandle = lookup.findVarHandle(cls, "isConnectionManagerActive", AtomicBoolean.class);
            activeConnectionRefHandle = lookup.findVarHandle(cls, "activeConnectionRef", AtomicReference.class);
            blockNodesHandle = lookup.findVarHandle(cls, "nodes", ConcurrentMap.class);
            nodeStatusTaskConnectionHandle = lookup.findVarHandle(
                    RetrieveBlockNodeStatusTask.class, "svcConnection", BlockNodeServiceConnection.class);
            globalActiveStreamingConnectionCountHandle =
                    lookup.findVarHandle(cls, "globalActiveStreamingConnectionCount", AtomicInteger.class);
            activeConfigRefHandle = lookup.findVarHandle(cls, "activeConfigRef", AtomicReference.class);
            globalCoolDownTimestampRefHandle =
                    lookup.findVarHandle(cls, "globalCoolDownTimestampRef", AtomicReference.class);
            bufferStatusRefHandle = lookup.findVarHandle(cls, "bufferStatusRef", AtomicReference.class);

            final Method isActiveConnectionAutoReset = cls.getDeclaredMethod(
                    "isActiveConnectionAutoReset", Instant.class, BlockNodeStreamingConnection.class);
            isActiveConnectionAutoReset.setAccessible(true);
            isActiveConnectionAutoResetHandle = lookup.unreflect(isActiveConnectionAutoReset);

            final Method isActiveConnectionStalled = cls.getDeclaredMethod(
                    "isActiveConnectionStalled", Instant.class, BlockNodeStreamingConnection.class);
            isActiveConnectionStalled.setAccessible(true);
            isActiveConnectionStalledHandle = lookup.unreflect(isActiveConnectionStalled);

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

            final Method isMissingActiveConnection =
                    cls.getDeclaredMethod("isMissingActiveConnection", BlockNodeStreamingConnection.class);
            isMissingActiveConnection.setAccessible(true);
            isMissingActiveConnectionHandle = lookup.unreflect(isMissingActiveConnection);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;
    private BlockNodeConfigService blockNodeConfigService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void beforeEach() {
        // Use a non-existent directory to prevent loading any existing block-nodes.json during tests
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        tempDir.toAbsolutePath().toString()));

        bufferService = mock(BlockBufferService.class);
        metrics = mock(BlockStreamMetrics.class);
        blockingIoExecutor = mock(ExecutorService.class);
        blockNodeConfigService = mock(BlockNodeConfigService.class);
        blockingIoExecutorSupplier = () -> blockingIoExecutor;
        connectionManager = new BlockNodeConnectionManager(
                configProvider, bufferService, metrics, blockingIoExecutorSupplier, blockNodeConfigService);

        // Clear any nodes that might have been loaded
        blockNodes().clear();

        // Ensure manager is not active
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        resetMocks();
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
    void testIsActiveConnectionStalled_nullConnection() throws Throwable {
        final boolean isStalled = invoke_isActiveConnectionStalled(Instant.now(), null);
        assertThat(isStalled).isFalse();
    }

    @Test
    void testIsActiveConnectionStalled_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // stalled connections are based on the heartbeat timestamp, so if we set the last heartbeat to near now
        // the connection will not be marked as stalled
        when(activeConnection.heartbeatTimestamp()).thenReturn(now.toEpochMilli());

        final boolean isStalled = invoke_isActiveConnectionStalled(now, activeConnection);

        assertThat(isStalled).isFalse();

        verify(activeConnection).heartbeatTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsActiveConnectionStalled_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        // stalled connections are based on the heartbeat timestamp, so set the last heartbeat to far in the past
        // to trigger a stall detection
        when(activeConnection.heartbeatTimestamp())
                .thenReturn(now.minusSeconds(1).toEpochMilli());

        final boolean isStalled = invoke_isActiveConnectionStalled(now, activeConnection);

        assertThat(isStalled).isTrue();

        verify(activeConnection).heartbeatTimestamp();
        verify(activeConnection).close(CloseReason.CONNECTION_STALLED, true);
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
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());

        blockNodes().clear();
        final BlockNode priority2Node =
                new BlockNode(newBlockNodeConfig("localhost", 1234, 2), new AtomicInteger(), new BlockNodeStats());
        final BlockNode activeNode = new BlockNode(activeConfig, new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(new BlockNodeEndpoint("localhost", 1234), priority2Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isFalse();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsHigherPriorityNodeAvailable_true() throws Throwable {
        final BlockNodeConfiguration activeConfig = newBlockNodeConfig("localhost", 9999, 2);
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        when(activeConnection.configuration()).thenReturn(activeConfig);
        when(activeConnection.connectionId()).thenReturn(new ConnectionId(ConnectionType.BLOCK_STREAMING, 1));
        when(activeConnection.createTimestamp()).thenReturn(Instant.now());
        when(activeConnection.activeTimestamp()).thenReturn(Instant.now());

        blockNodes().clear();
        final BlockNode priority1Node =
                new BlockNode(newBlockNodeConfig("localhost", 1234, 1), new AtomicInteger(), new BlockNodeStats());
        final BlockNode activeNode = new BlockNode(activeConfig, new AtomicInteger(), new BlockNodeStats());
        activeNode.onActive(activeConnection);
        blockNodes().put(priority1Node.configuration().streamingEndpoint(), priority1Node);
        blockNodes().put(activeConfig.streamingEndpoint(), activeNode);

        final boolean isHigherAvailable = invoke_isHigherPriorityNodeAvailable(activeConnection);

        assertThat(isHigherAvailable).isTrue();

        verify(activeConnection, atLeastOnce()).configuration();
        verify(activeConnection, times(2)).connectionId();
        verify(activeConnection).createTimestamp();
        verify(activeConnection).activeTimestamp();
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsBufferUnhealthy_latestStatusNull() throws Throwable {
        when(bufferService.latestBufferStatus()).thenReturn(null);

        final boolean isBufferUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isBufferUnhealthy).isFalse();

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_sameStatus() throws Throwable {
        final BlockBufferStatus status = new BlockBufferStatus(Instant.now(), 5.00D, false);
        when(bufferService.latestBufferStatus()).thenReturn(status);
        bufferStatusRef().set(status);

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_false() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferStatus latestStatus = new BlockBufferStatus(now, 5.00D, false);
        when(bufferService.latestBufferStatus()).thenReturn(latestStatus);
        bufferStatusRef().set(new BlockBufferStatus(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isFalse();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
    }

    @Test
    void testIsBufferUnhealthy_true() throws Throwable {
        final Instant now = Instant.now();
        final BlockBufferStatus latestStatus = new BlockBufferStatus(now, 80.00D, true);
        when(bufferService.latestBufferStatus()).thenReturn(latestStatus);
        bufferStatusRef().set(new BlockBufferStatus(now.minusSeconds(1), 0.0D, false));

        final boolean isUnhealthy = invoke_isBufferUnhealthy();

        assertThat(isUnhealthy).isTrue();
        assertThat(bufferStatusRef()).hasValue(latestStatus);

        verify(bufferService).latestBufferStatus();
        verifyNoMoreInteractions(bufferService);
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
        blockNodes().put(config.streamingEndpoint(), new BlockNode(config, new AtomicInteger(), new BlockNodeStats()));

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
        final boolean isMissing = invoke_isMissingActiveConnection(activeConnection);
        assertThat(isMissing).isFalse();
    }

    @Test
    void testIsMissingActiveConnection_true() throws Throwable {
        final boolean isMissing = invoke_isMissingActiveConnection(null);
        assertThat(isMissing).isTrue();
    }

    // Utilities

    boolean invoke_isMissingActiveConnection(final BlockNodeStreamingConnection activeConnection) throws Throwable {
        return (boolean) isMissingActiveConnectionHandle.invoke(connectionManager, activeConnection);
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

    boolean invoke_isActiveConnectionStalled(final Instant now, final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isActiveConnectionStalledHandle.invoke(connectionManager, now, activeConnection);
    }

    boolean invoke_isActiveConnectionAutoReset(final Instant now, final BlockNodeStreamingConnection activeConnection)
            throws Throwable {
        return (boolean) isActiveConnectionAutoResetHandle.invoke(connectionManager, now, activeConnection);
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
    AtomicReference<BlockBufferStatus> bufferStatusRef() {
        return (AtomicReference<BlockBufferStatus>) bufferStatusRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<BlockNodeStreamingConnection> activeConnection() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<BlockNodeEndpoint, BlockNode> blockNodes() {
        return (ConcurrentMap<BlockNodeEndpoint, BlockNode>) blockNodesHandle.get(connectionManager);
    }

    private AtomicInteger globalActiveStreamingConnectionCount() {
        return (AtomicInteger) globalActiveStreamingConnectionCountHandle.get(connectionManager);
    }

    private AtomicBoolean isActiveFlag() {
        return (AtomicBoolean) isManagerActiveHandle.get(connectionManager);
    }

    private void awaitCondition(final BooleanSupplier condition, final long timeoutMs) {
        final long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void resetMocks() {
        reset(bufferService, metrics, blockNodeConfigService);
    }
}
