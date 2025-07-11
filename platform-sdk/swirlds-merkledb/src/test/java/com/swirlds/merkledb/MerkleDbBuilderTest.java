// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleDbBuilderTest {

    private static final int MAPS_COUNT = 3;
    private static final int ITERATIONS = 20;
    private static final int ROUND_CHANGES = 1000;

    private static final long INITIAL_SIZE = 1_000_000;

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
    }

    @AfterEach
    public void afterCheckNoDbLeftOpen() {
        // check db count
        assertEquals(0, MerkleDbDataSource.getCountOfOpenDatabases(), "Expected no open dbs");
    }

    final MerkleDbDataSourceBuilder createDefaultBuilder() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbDataSourceBuilder(CONFIGURATION, INITIAL_SIZE, merkleDbConfig.hashesRamToDiskThreshold());
    }

    private void verify(final MerkleInternal stateRoot) {
        for (int i = 0; i < MAPS_COUNT; i++) {
            final VirtualMap<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> vm = stateRoot.getChild(i);
            final VirtualMapState state = vm.getLeft();
            final VirtualRootNode<ExampleLongKeyFixedSize, ExampleFixedSizeVirtualValue> root = vm.getRight();
            for (int path = 0; path <= state.getLastLeafPath(); path++) {
                final Hash hash = root.getRecords().findHash(path);
                assertNotNull(hash);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"100,0", "100,100", "1000000,1024", "1000000,9223372036854775807"})
    @DisplayName("Test table config is passed to data source")
    public void testTableConfig(final long initialCapacity, final long hashesRamToDiskThreshold) throws IOException {
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, initialCapacity, hashesRamToDiskThreshold);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test1", false);
            assertTrue(dataSource instanceof MerkleDbDataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(initialCapacity, merkleDbDataSource.getInitialCapacity());
            assertEquals(hashesRamToDiskThreshold, merkleDbDataSource.getHashesRamToDiskThreshold());
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Test compaction flag is passed to data source")
    public void testCompactionConfig(final boolean compactionEnabled) throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024, 0);
        VirtualDataSource dataSource = null;
        try {
            dataSource = builder.build("test2", compactionEnabled);
            assertTrue(dataSource instanceof MerkleDbDataSource);
            MerkleDbDataSource merkleDbDataSource = (MerkleDbDataSource) dataSource;
            assertEquals(compactionEnabled, merkleDbDataSource.isCompactionEnabled());
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshot() throws IOException {
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 1024, 1024);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshot";
            dataSource = builder.build(label, false);
            final Path tmpDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
        } finally {
            dataSource.close();
        }
    }

    @Test
    void testSnapshotRestore() throws IOException {
        final int hashesRamToDiskThreshold = 4096;
        final MerkleDbDataSourceBuilder builder =
                new MerkleDbDataSourceBuilder(CONFIGURATION, 10_000, hashesRamToDiskThreshold);
        VirtualDataSource dataSource = null;
        try {
            final String label = "testSnapshotRestore";
            dataSource = builder.build(label, false);
            final Path tmpDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshot", CONFIGURATION);
            builder.snapshot(tmpDir, dataSource);
            assertTrue(Files.isDirectory(tmpDir.resolve("data").resolve(label)));
            VirtualDataSource restored = null;
            try {
                restored = builder.restore(label, tmpDir, false);
                assertNotNull(restored);
                assertTrue(restored instanceof MerkleDbDataSource);
                final MerkleDbDataSource merkleDbRestored = (MerkleDbDataSource) restored;
                assertEquals(hashesRamToDiskThreshold, merkleDbRestored.getHashesRamToDiskThreshold());
            } finally {
                restored.close();
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void snapshotMultipleTablesTestSync() throws Exception {
        final MerkleInternal initialRoot = new TestInternalNode();
        final MerkleDbDataSourceBuilder dsBuilder = createDefaultBuilder();
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
        final MerkleDbDataSourceBuilder dsBuilder = createDefaultBuilder();
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
        final MerkleDbDataSourceBuilder dsBuilder = createDefaultBuilder();
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
