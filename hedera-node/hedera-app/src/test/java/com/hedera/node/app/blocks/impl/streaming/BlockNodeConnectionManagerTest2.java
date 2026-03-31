// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.RetrieveBlockNodeStatusTask;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
public class BlockNodeConnectionManagerTest2 extends BlockNodeCommunicationTestBase {

    private static final VarHandle isManagerActiveHandle;
    private static final VarHandle activeConnectionRefHandle;
    private static final VarHandle nodeStatusTaskConnectionHandle;
    private static final VarHandle blockNodesHandle;
    private static final VarHandle globalActiveStreamingConnectionCountHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.lookup();
            isManagerActiveHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "isConnectionManagerActive", AtomicBoolean.class);
            activeConnectionRefHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "activeConnectionRef", AtomicReference.class);
            blockNodesHandle = MethodHandles.privateLookupIn(BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(BlockNodeConnectionManager.class, "nodes", ConcurrentMap.class);
            nodeStatusTaskConnectionHandle = MethodHandles.privateLookupIn(RetrieveBlockNodeStatusTask.class, lookup)
                    .findVarHandle(
                            RetrieveBlockNodeStatusTask.class, "svcConnection", BlockNodeServiceConnection.class);
            globalActiveStreamingConnectionCountHandle = MethodHandles.privateLookupIn(
                            BlockNodeConnectionManager.class, lookup)
                    .findVarHandle(
                            BlockNodeConnectionManager.class,
                            "globalActiveStreamingConnectionCount",
                            AtomicInteger.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BlockNodeConnectionManager connectionManager;

    private BlockBufferService bufferService;
    private BlockStreamMetrics metrics;
    private ExecutorService blockingIoExecutor;
    private Supplier<ExecutorService> blockingIoExecutorSupplier;

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
        blockingIoExecutorSupplier = () -> blockingIoExecutor;
        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);

        // Clear any nodes that might have been loaded
        blockNodes().clear();

        // Ensure manager is not active
        final AtomicBoolean isActive = isActiveFlag();
        isActive.set(false);

        resetMocks();
    }

    @Test
    void testShutdown_managerNotActive() {}

    @Test
    void testShutdown() {}

    @Test
    void testStart_streamingNotEnabled() {}

    @Test
    void testStart_managerAlreadyActive() {}

    @Test
    void testStart() {}

    @Test
    void testRetrieveBlockNodeStatusTask_failure() {}

    @Test
    void testRetrieveBlockNodeStatusTask() {}

    @Test
    void testNotifyConnectionClosed_unknownNode() {}

    @Test
    void testNotifyConnectionClosed() {}

    // Utilities

    private void createConnectionManager(final List<BlockNodeConfiguration> blockNodes) {
        // Create a custom config provider with the specified block nodes
        final ConfigProvider configProvider = createConfigProvider(createDefaultConfigProvider()
                .withValue("blockNode.blockNodeConnectionFileDir", "/tmp/non-existent-test-dir-" + System.nanoTime()));

        // Create the manager
        connectionManager =
                new BlockNodeConnectionManager(configProvider, bufferService, metrics, blockingIoExecutorSupplier);

        // Set the available nodes using reflection
        try {
            final ConcurrentMap<String, BlockNode> nodes = blockNodes();
            final AtomicInteger globalActiveStreamingConnectionCount = globalActiveStreamingConnectionCount();
            nodes.clear();
            blockNodes.forEach(bnCfg -> nodes.put(
                    bnCfg.address(), new BlockNode(bnCfg, globalActiveStreamingConnectionCount, new BlockNodeStats())));

        } catch (final Throwable t) {
            throw new RuntimeException("Failed to set available nodes", t);
        }
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<BlockNodeStreamingConnection> activeConnection() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeConnectionRefHandle.get(connectionManager);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, BlockNode> blockNodes() {
        return (ConcurrentMap<String, BlockNode>) blockNodesHandle.get(connectionManager);
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
        reset(bufferService, metrics);
    }

    private void useStreamingDisabledManager() {
        // Recreate connectionManager with streaming disabled (writerMode=FILE)
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE")
                .withValue(
                        "blockNode.blockNodeConnectionFileDir",
                        Objects.requireNonNull(BlockNodeCommunicationTestBase.class
                                        .getClassLoader()
                                        .getResource("bootstrap/"))
                                .getPath())
                .getOrCreateConfig();
        final ConfigProvider disabledProvider = () -> new VersionedConfigImpl(config, 1L);
        connectionManager =
                new BlockNodeConnectionManager(disabledProvider, bufferService, metrics, blockingIoExecutorSupplier);
    }

    private static <T> CompletableFuture<T> createSleepingFuture() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(30_000);
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }

            return null;
        });
    }
}
