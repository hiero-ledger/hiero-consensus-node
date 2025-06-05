// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.ExampleFixedSizeVirtualValue;
import com.swirlds.merkledb.test.fixtures.ExampleFixedSizeVirtualValueSerializer;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyFixedSize;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualMapState;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MerkleDbSnapshotTest {

    private static final int MAPS_COUNT = 3;
    private static final int ITERATIONS = 20;
    private static final int ROUND_CHANGES = 1000;

    private static final Random RANDOM = new Random(123);

    private static final KeySerializer<ExampleLongKeyFixedSize> keySerializer =
            new ExampleLongKeyFixedSize.Serializer();
    private static final ValueSerializer<ExampleFixedSizeVirtualValue> valueSerializer =
            new ExampleFixedSizeVirtualValueSerializer();

    @BeforeAll
    static void setup() throws Exception {
        ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
        registry.registerConstructables("com.swirlds.merkledb");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructable(new ClassConstructorPair(
                MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(CONFIGURATION)));
        registry.registerConstructable(new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(CONFIGURATION)));
        registry.registerConstructable(new ClassConstructorPair(
                VirtualNodeCache.class,
                () -> new VirtualNodeCache(CONFIGURATION.getConfigData(VirtualMapConfig.class))));
        registry.registerConstructable(new ClassConstructorPair(
                MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(CONFIGURATION)));
    }

    @BeforeEach
    void setupTest() throws Exception {
        MerkleDb.setDefaultPath(
                LegacyTemporaryFileBuilder.buildTemporaryDirectory("MerkleDbSnapshotTest", CONFIGURATION));
    }

    @AfterEach
    public void afterTest() {
        // check db count
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    private static MerkleDbTableConfig fixedConfig() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, 1_000_000, merkleDbConfig.hashesRamToDiskThreshold());
    }

    private void verify(final MerkleInternal stateRoot) {
        for (int i = 0; i < MAPS_COUNT; i++) {
            final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm = stateRoot.getChild(i);
            final VirtualMapState state = vm.getLeft();
            final VirtualRootNode<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> root = vm.getRight();
            for (int path = 0; path <= state.getLastLeafPath(); path++) {
                final Hash hash = root.getRecords().findHash(path);
                Assertions.assertNotNull(hash);
            }
        }
    }

    @Test
    void snapshotMultipleTablesTestSync() throws Exception {
        final MerkleInternal initialRoot = new TestInternalNode();
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        for (int i = 0; i < MAPS_COUNT; i++) {
            final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm =
                    new VirtualMap<>("vm" + i, keySerializer, valueSerializer, dsBuilder, CONFIGURATION);
            registerMetrics(vm);
            initialRoot.setChild(i, vm);
        }

        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshotSync", CONFIGURATION);
        final Path snapshotFile = snapshotDir.resolve("state.swh");

        final AtomicReference<MerkleInternal> lastRoot = new AtomicReference<>();
        MerkleInternal stateRoot = initialRoot;
        long keyId = 0;
        for (int j = 0; j < ITERATIONS; j++) {
            final MerkleInternal newStateRoot = stateRoot.copy();
            for (int i = 0; i < MAPS_COUNT; i++) {
                final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm = newStateRoot.getChild(i);
                final VirtualRootNode<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> root = vm.getRight();
                root.enableFlush();
                for (int k = 0; k < ROUND_CHANGES; k++) {
                    final ExampleLongKeyFixedSize key = new ExampleLongKeyFixedSize(keyId++);
                    final ExampleFixedSizeVirtualValue value = new ExampleFixedSizeVirtualValue(RANDOM.nextInt());
                    vm.put(key, value);
                }
            }
            if (j == ITERATIONS / 2) {
                TestMerkleCryptoFactory.getInstance().digestTreeSync(stateRoot);
                final MerkleDataOutputStream out = new MerkleDataOutputStream(
                        Files.newOutputStream(snapshotFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                out.writeMerkleTree(snapshotDir, stateRoot);
            }
            stateRoot.release();
            stateRoot = newStateRoot;
        }
        lastRoot.set(stateRoot);

        MerkleDb.resetDefaultInstancePath();
        final MerkleDataInputStream in =
                new MerkleDataInputStream(Files.newInputStream(snapshotFile, StandardOpenOption.READ));
        final MerkleInternal restoredStateRoot = in.readMerkleTree(snapshotDir, Integer.MAX_VALUE);

        verify(restoredStateRoot);

        lastRoot.get().release();
        restoredStateRoot.release();
        closeDataSources(initialRoot);
        closeDataSources(lastRoot.get());
        closeDataSources(restoredStateRoot);
    }

    @Test
    void snapshotMultipleTablesTestAsync() throws Exception {
        final MerkleInternal initialRoot = new TestInternalNode();
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        for (int i = 0; i < MAPS_COUNT; i++) {
            final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm =
                    new VirtualMap<>("vm" + i, keySerializer, valueSerializer, dsBuilder, CONFIGURATION);
            initialRoot.setChild(i, vm);
        }

        final AtomicReference<MerkleInternal> rootToSnapshot = new AtomicReference<>();
        final AtomicReference<MerkleInternal> lastRoot = new AtomicReference<>();
        final CountDownLatch startSnapshotLatch = new CountDownLatch(1);
        new Thread(() -> {
                    MerkleInternal stateRoot = initialRoot;
                    long keyId = 0;
                    for (int j = 0; j < ITERATIONS; j++) {
                        final MerkleInternal newStateRoot = stateRoot.copy();
                        for (int i = 0; i < MAPS_COUNT; i++) {
                            final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm =
                                    newStateRoot.getChild(i);
                            final VirtualRootNode<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> root =
                                    vm.getRight();
                            root.enableFlush();
                            for (int k = 0; k < ROUND_CHANGES; k++) {
                                final ExampleLongKeyFixedSize key = new ExampleLongKeyFixedSize(keyId++);
                                final ExampleFixedSizeVirtualValue value =
                                        new ExampleFixedSizeVirtualValue(RANDOM.nextInt());
                                vm.put(key, value);
                            }
                        }
                        if (j == ITERATIONS / 2) {
                            rootToSnapshot.set(stateRoot);
                            startSnapshotLatch.countDown();
                        } else {
                            stateRoot.release();
                        }
                        stateRoot = newStateRoot;
                    }
                    lastRoot.set(stateRoot);
                })
                .start();

        startSnapshotLatch.await();
        assertEventuallyTrue(() -> lastRoot.get() != null, Duration.ofSeconds(10), "lastRoot is null");

        TestMerkleCryptoFactory.getInstance().digestTreeSync(rootToSnapshot.get());
        final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshotAsync", CONFIGURATION);
        final Path snapshotFile = snapshotDir.resolve("state.swh");
        final MerkleDataOutputStream out = new MerkleDataOutputStream(
                Files.newOutputStream(snapshotFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        out.writeMerkleTree(snapshotDir, rootToSnapshot.get());
        rootToSnapshot.get().release();

        MerkleDb.resetDefaultInstancePath();
        final MerkleDataInputStream in =
                new MerkleDataInputStream(Files.newInputStream(snapshotFile, StandardOpenOption.READ));
        final MerkleInternal restoredStateRoot = in.readMerkleTree(snapshotDir, Integer.MAX_VALUE);

        verify(restoredStateRoot);

        lastRoot.get().release();
        restoredStateRoot.release();
        closeDataSources(initialRoot);
        closeDataSources(restoredStateRoot);
    }

    private static void closeDataSources(MerkleInternal initialRoot) throws IOException, InterruptedException {
        for (int i = 0; i < MAPS_COUNT; i++) {
            final VirtualMap<?, ?> vm = initialRoot.getChild(i);
            ((VirtualRootNode<?, ?>) vm.getRight()).getPipeline().awaitTermination(8, TimeUnit.SECONDS);
            vm.getDataSource().close();
        }
    }

    /*
     * This test simulates the following scenario. First, a signed state for round N is selected
     * to be flushed to disk (periodic snapshot). Before it's done, the node is disconnected from
     * network and starts a reconnect. Reconnect is successful for a different round M (M > N),
     * and snapshot for round M is written to disk. Now the node has all signatures for the old
     * round N, and that old signed state is finally written to disk.
     */
    @Test
    void testSnapshotAfterReconnect() throws Exception {
        final MerkleDbTableConfig tableConfig = fixedConfig();
        final MerkleDbDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        final VirtualDataSource original = dsBuilder.build("vm", false);
        // Simulate reconnect as a learner
        final VirtualDataSource copy = dsBuilder.copy(original, true, false);

        try {
            final Path snapshotDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            dsBuilder.snapshot(snapshotDir, copy);

            final Path oldSnapshotDir =
                    LegacyTemporaryFileBuilder.buildTemporaryDirectory("oldSnapshot", CONFIGURATION);
            assertDoesNotThrow(() -> dsBuilder.snapshot(oldSnapshotDir, original));
        } finally {
            original.close();
            copy.close();
        }
    }

    private static void registerMetrics(VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm) {
        final Configuration CONFIGURATION = new TestConfigBuilder().getOrCreateConfig();
        MetricsConfig metricsConfig = CONFIGURATION.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        MerkleDbStatistics statistics =
                new MerkleDbStatistics(CONFIGURATION.getConfigData(MerkleDbConfig.class), "test");
        statistics.registerMetrics(metrics);
        vm.getDataSource().registerMetrics(metrics);
    }

    public static class TestInternalNode extends PartialNaryMerkleInternal implements MerkleInternal {

        public TestInternalNode() {}

        public TestInternalNode(final TestInternalNode that) {
            for (int i = 0; i < that.getNumberOfChildren(); i++) {
                setChild(i, that.getChild(i).copy());
            }
        }

        @Override
        public long getClassId() {
            return 1357924680L;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public MerkleInternal copy() {
            return new TestInternalNode(this);
        }
    }
}
