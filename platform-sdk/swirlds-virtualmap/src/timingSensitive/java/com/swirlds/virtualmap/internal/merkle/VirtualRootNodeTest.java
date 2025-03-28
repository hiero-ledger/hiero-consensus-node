// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createRoot;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.streams.SerializableDataInputStreamImpl;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode.ClassVersion;
import com.swirlds.virtualmap.test.fixtures.DummyVirtualStateAccessor;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.InMemoryDataSource;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestKeySerializer;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueSerializer;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("ALL")
class VirtualRootNodeTest extends VirtualTestBase {

    @TempDir
    private Path tempDir;

    void testEnableVirtualRootFlush() throws ExecutionException, InterruptedException {
        VirtualRootNode<TestKey, TestValue> fcm0 = createRoot();
        fcm0.postInit(new DummyVirtualStateAccessor());
        assertFalse(fcm0.shouldBeFlushed(), "map should not yet be flushed");

        VirtualRootNode<TestKey, TestValue> fcm1 = fcm0.copy();
        fcm1.postInit(new DummyVirtualStateAccessor());
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualRootNode<TestKey, TestValue> fcm2 = fcm1.copy();
        fcm2.postInit(new DummyVirtualStateAccessor());
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualRootNode<TestKey, TestValue> fcm3 = fcm2.copy();
        fcm3.postInit(new DummyVirtualStateAccessor());
        fcm3.enableFlush();
        assertTrue(fcm3.shouldBeFlushed(), "map should now be flushed");

        fcm0.release();
        fcm1.release();
        fcm2.release();
        fcm3.release();
    }

    @Test
    @DisplayName("A new map with a datasource with a root hash reveals it")
    void mapWithExistingHashedDataHasNonNullRootHash() throws ExecutionException, InterruptedException {
        // The builder I will use with this map is unique in that each call to "build" returns THE SAME DATASOURCE.
        final InMemoryDataSource ds = new InMemoryDataSource("mapWithExistingHashedDataHasNonNullRootHash");
        final VirtualDataSourceBuilder builder = new InMemoryBuilder();

        final VirtualRootNode<TestKey, TestValue> fcm = new VirtualRootNode<>(
                TestKeySerializer.INSTANCE,
                TestValueSerializer.INSTANCE,
                builder,
                CONFIGURATION.getConfigData(VirtualMapConfig.class));
        fcm.postInit(new DummyVirtualStateAccessor());
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE);

        final VirtualRootNode<TestKey, TestValue> copy = fcm.copy();
        copy.postInit(fcm.getState());

        fcm.getHash();
        final Hash expectedHash = fcm.getChild(0).getHash();
        fcm.release();
        fcm.waitUntilFlushed();

        final VirtualRootNode<TestKey, TestValue> fcm2 = new VirtualRootNode<>(
                TestKeySerializer.INSTANCE,
                TestValueSerializer.INSTANCE,
                builder,
                CONFIGURATION.getConfigData(VirtualMapConfig.class));
        fcm2.postInit(copy.getState());
        assertNotNull(fcm2.getChild(0), "child should not be null");
        assertEquals(expectedHash, fcm2.getChild(0).getHash(), "hash should match expected");

        copy.release();
        fcm2.release();
    }

    @Test
    @DisplayName("Remove only element")
    void removeOnlyElement() throws ExecutionException, InterruptedException {

        final VirtualRootNode<TestKey, TestValue> fcm = createRoot();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE);

        final VirtualRootNode<TestKey, TestValue> copy = fcm.copy();
        copy.postInit(fcm.getState());
        fcm.release();
        fcm.waitUntilFlushed();

        final TestValue removed = copy.remove(A_KEY);
        assertEquals(APPLE, removed, "Wrong value");

        // FUTURE WORK validate hashing works as expected

        copy.release();
    }

    @Test
    @DisplayName("Remove element twice")
    void removeElementTwice() throws ExecutionException, InterruptedException {
        final VirtualRootNode<TestKey, TestValue> fcm = createRoot();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE);
        fcm.put(B_KEY, BANANA);
        fcm.put(C_KEY, CHERRY);

        final VirtualRootNode<TestKey, TestValue> copy = fcm.copy();
        copy.postInit(fcm.getState());
        fcm.release();
        fcm.waitUntilFlushed();

        final TestValue removed = copy.remove(B_KEY);
        final TestValue removed2 = copy.remove(B_KEY);
        assertEquals(BANANA, removed, "Wrong value");
        assertNull(removed2, "Expected null");
        copy.release();
    }

    @Test
    @DisplayName("Remove elements in reverse order")
    void removeInReverseOrder() throws ExecutionException, InterruptedException {
        final VirtualRootNode<TestKey, TestValue> fcm = createRoot();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE);
        fcm.put(B_KEY, BANANA);
        fcm.put(C_KEY, CHERRY);
        fcm.put(D_KEY, DATE);
        fcm.put(E_KEY, EGGPLANT);
        fcm.put(F_KEY, FIG);
        fcm.put(G_KEY, GRAPE);

        final VirtualRootNode<TestKey, TestValue> copy = fcm.copy();
        copy.postInit(fcm.getState());
        fcm.release();
        fcm.waitUntilFlushed();

        assertEquals(GRAPE, copy.remove(G_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, F_KEY, B_KEY, D_KEY);
        assertEquals(FIG, copy.remove(F_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, B_KEY, D_KEY);
        assertEquals(EGGPLANT, copy.remove(E_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, C_KEY, B_KEY, D_KEY);
        assertEquals(DATE, copy.remove(D_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, C_KEY, B_KEY);
        assertEquals(CHERRY, copy.remove(C_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, B_KEY);
        assertEquals(BANANA, copy.remove(B_KEY), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY);
        assertEquals(APPLE, copy.remove(A_KEY), "Wrong value");

        // FUTURE WORK validate hashing works as expected

        copy.release();
    }

    /**
     * This test deserializes a VirtualRootNode that was serialized with version 1 of the serialization format.
     * This node contains 100 entries, but only 88 of them are valid. The other 12 are deleted.
     */
    @Test
    void testDeserializeFromFileOfVersion2() throws IOException, InterruptedException {
        deserializeRootNodeAndVerify(
                getClass().getResourceAsStream("/virtualRootNode_ver2/rootNode.bin"),
                ClassVersion.VERSION_2_KEYVALUE_SERIALIZERS);
    }

    /**
     * This test deserializes a VirtualRootNode that was serialized with version 2 of the serialization format.
     * This node contains 100 entries, but only 88 of them are valid. The other 12 are deleted.
     */
    @Test
    void testSerializeDeserialize() throws IOException {
        String fileName = "rootNode.bin";
        serializeRoot(fileName);
        deserializeRootNodeAndVerify(
                new FileInputStream(tempDir.resolve(fileName).toFile()), ClassVersion.CURRENT_VERSION);
    }

    private void deserializeRootNodeAndVerify(InputStream resourceAsStream, int version) throws IOException {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();

        try (SerializableDataInputStream input = new SerializableDataInputStreamImpl(resourceAsStream)) {
            root.deserialize(input, tempDir, version);
            root.postInit(new DummyVirtualStateAccessor());
            final VirtualNodeCache<TestKey, TestValue> cache = root.getCache();
            for (int i = 0; i < 100; i++) {
                final TestKey key = new TestKey(i);
                if (version >= ClassVersion.VERSION_3_NO_NODE_CACHE) {
                    // Cache must be empty, all values must be in the data source
                    assertNull(cache.lookupLeafByKey(key, false));
                }
                if (i % 7 != 0) {
                    assertEquals(new TestValue(i), root.get(key));
                } else {
                    assertNull(root.get(new TestKey(i)));
                }
            }
            root.release();
        }
    }

    private void serializeRoot(String fileName) throws IOException {
        try (FileOutputStream fileOutputStream =
                        new FileOutputStream(tempDir.resolve(fileName).toFile());
                SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(fileOutputStream)) {
            VirtualRootNode<TestKey, TestValue> testKeyTestValueVirtualRootNode = prepareRootForSerialization();
            testKeyTestValueVirtualRootNode.serialize(out, tempDir);
            fileOutputStream.flush();
            testKeyTestValueVirtualRootNode.release();
        }
    }

    private static VirtualRootNode<TestKey, TestValue> prepareRootForSerialization() {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.enableFlush();

        Set<TestKey> keysToRemove = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            root.put(new TestKey(i), new TestValue(i));
            if (i % 7 == 0) {
                keysToRemove.add(new TestKey(i));
            }
        }

        for (TestKey key : keysToRemove) {
            root.remove(key);
        }
        root.computeHash();
        root.setImmutable(true);
        return root;
    }

    /**
     * This is a preliminary example of how to move data from one VirtualMap
     * to another.
     *
     * @throws InterruptedException
     * 		if the thread is interrupted during sleep
     */
    @Test
    @Tags({@Tag("VMAP-013")})
    void moveDataAcrossMaps() throws InterruptedException {
        final int totalSize = 1_000_000;
        final VirtualRootNode<TestKey, TestValue> root1 = createRoot();
        for (int index = 0; index < totalSize; index++) {
            final TestKey key = new TestKey(index);
            final TestValue value = new TestValue(index);
            root1.put(key, value);
        }

        final VirtualRootNode<TestKey, TestValue> root2 = createRoot();
        final long firstLeafPath = root1.getState().getFirstLeafPath();
        final long lastLeafPath = root1.getState().getLastLeafPath();
        for (long index = firstLeafPath; index <= lastLeafPath; index++) {
            final VirtualLeafRecord<TestKey, TestValue> leaf =
                    root1.getRecords().findLeafRecord(index, false);
            final TestKey key = leaf.getKey().copy();
            final TestValue value = leaf.getValue().copy();
            root2.put(key, value);
        }

        for (int index = 0; index < totalSize; index++) {
            final TestKey key = new TestKey(index);
            root1.remove(key);
        }

        assertTrue(root1.isEmpty(), "All elements have been removed");
        root1.release();
        TimeUnit.MILLISECONDS.sleep(100);
        System.gc();
        assertEquals(totalSize, root2.size(), "New map still has all data");
        for (int index = 0; index < totalSize; index++) {
            final TestKey key = new TestKey(index);
            final TestValue expectedValue = new TestValue(index);
            final TestValue value = root2.get(key);
            assertEquals(expectedValue, value, "Values have the same content");
        }
    }

    @Test
    @DisplayName("Snapshot Test")
    void snapshotTest() throws IOException {
        final List<Path> paths = new LinkedList<>();
        paths.add(Path.of("asdf"));
        for (final Path destination : paths) {
            final VirtualMap<TestKey, TestValue> original = new VirtualMap<>(
                    "test", new TestKeySerializer(), new TestValueSerializer(), new InMemoryBuilder(), CONFIGURATION);
            final VirtualMap<TestKey, TestValue> copy = original.copy();

            final VirtualRootNode<TestKey, TestValue> root = original.getChild(1);
            root.getHash(); // forces copy to become hashed
            root.getPipeline().pausePipelineAndRun("snapshot", () -> {
                root.snapshot(destination);
                return null;
            });
            assertTrue(root.isDetached(), "root should be detached");

            original.release();
            copy.release();
        }
    }

    @Test
    @DisplayName("Snapshot and restore")
    void snapshotAndRestore() throws IOException {
        final VirtualDataSourceBuilder dsBuilder = new InMemoryBuilder();
        final List<VirtualMap<TestKey, TestValue>> copies = new LinkedList<>();
        final VirtualMap<TestKey, TestValue> copy0 =
                new VirtualMap<>("test", new TestKeySerializer(), new TestValueSerializer(), dsBuilder, CONFIGURATION);
        copies.add(copy0);
        for (int i = 1; i <= 10; i++) {
            final VirtualMap<TestKey, TestValue> prevCopy = copies.get(i - 1);
            final VirtualMap<TestKey, TestValue> copy = prevCopy.copy();
            // i-th copy contains TestKey(i)
            copy.put(new TestKey(i), new TestValue(i + 100));
            copies.add(copy);
        }
        for (VirtualMap<TestKey, TestValue> copy : copies) {
            // Force virtual map / root node hashing
            copy.getRight().getHash();
        }
        // Take a snapshot of copy 5
        final VirtualMap<TestKey, TestValue> copy5 = copies.get(5);
        final Path snapshotPath =
                LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshotAndRestore", CONFIGURATION);
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream();
                final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(bout)) {
            copy5.serialize(out, snapshotPath);
            try (final ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
                    final SerializableDataInputStream in = new SerializableDataInputStreamImpl(bin)) {
                final VirtualMap<TestKey, TestValue> restored = new VirtualMap<>(CONFIGURATION);
                restored.deserialize(in, snapshotPath, copy0.getVersion());
                // All keys 1 to 5 should be in the snapshot
                for (int i = 1; i < 6; i++) {
                    final TestKey key = new TestKey(i);
                    assertTrue(restored.containsKey(key), "Key " + i + " not found");
                    assertEquals(new TestValue(i + 100), restored.get(key));
                }
                // All keys 6 to 10 should not be there
                for (int i = 6; i < 10; i++) {
                    final TestKey key = new TestKey(i);
                    assertFalse(restored.containsKey(key), "Key " + i + " found");
                    assertNull(restored.get(key));
                }
            }
        } finally {
            copies.forEach(VirtualMap::release);
        }
    }

    @Test
    @DisplayName("Detach Test")
    void detachTest() throws IOException {
        final VirtualMap<TestKey, TestValue> original = new VirtualMap<>(
                "test", new TestKeySerializer(), new TestValueSerializer(), new InMemoryBuilder(), CONFIGURATION);
        final VirtualMap<TestKey, TestValue> copy = original.copy();

        final VirtualRootNode<TestKey, TestValue> root = original.getChild(1);
        root.getHash(); // forces copy to become hashed
        final RecordAccessor<TestKey, TestValue> detachedCopy =
                root.getPipeline().pausePipelineAndRun("copy", root::detach);
        assertTrue(root.isDetached(), "root should be detached");
        assertNotNull(detachedCopy);

        original.release();
        copy.release();
        detachedCopy.getDataSource().close();
    }

    @Test
    @DisplayName("Default flush threshold not zero")
    void defaultFlushThresholdTest() {
        final VirtualMapConfig config =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);
        VirtualRootNode<TestKey, TestValue> root = createRoot();
        assertEquals(config.copyFlushThreshold(), root.getFlushThreshold());
        root.release();
    }

    @Test
    @DisplayName("Flush interval is inherited by copies")
    void flushIntervalInheritedTest() {
        final long threshold = 12345678L;
        final VirtualMapConfig config =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);

        final int flushInterval = config.flushInterval();
        VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.setFlushThreshold(threshold);
        for (int i = 0; i <= flushInterval; i++) {
            assertEquals(threshold, root.getFlushThreshold());
            VirtualRootNode<TestKey, TestValue> copy = root.copy();
            copy.postInit(root.getState());
            root.release();
            root = copy;
        }
        root.release();
    }

    @Test
    @DisplayName("Zero flush threshold enables round based flushes")
    void zeroFlushThresholdTest() {
        final VirtualMapConfig config =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);
        final int flushInterval = config.flushInterval();
        VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.setFlushThreshold(0);
        assertFalse(root.shouldBeFlushed()); // the very first copy is never flushed
        for (int i = 0; i < flushInterval; i++) {
            VirtualRootNode<TestKey, TestValue> copy = root.copy();
            copy.postInit(root.getState());
            root.release();
            root = copy;
        }
        assertTrue(root.shouldBeFlushed());
        root.release();
    }

    @Test
    @DisplayName("Default zero flush threshold")
    void defaultZeroFlushThresholdTest() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(VirtualMapConfig_.COPY_FLUSH_THRESHOLD, "0")
                .getOrCreateConfig();

        VirtualRootNode<TestKey, TestValue> root = createRoot(configuration);
        assertEquals(0, root.getFlushThreshold());
        final int flushInterval =
                configuration.getConfigData(VirtualMapConfig.class).flushInterval();
        for (int i = 0; i < flushInterval; i++) {
            VirtualRootNode<TestKey, TestValue> copy = root.copy();
            copy.postInit(root.getState());
            root.release();
            root = copy;
        }
        assertTrue(root.shouldBeFlushed());
        root.setFlushThreshold(12345678L);
        assertTrue(root.shouldBeFlushed());
        for (int i = 0; i < flushInterval; i++) {
            VirtualRootNode<TestKey, TestValue> copy = root.copy();
            copy.postInit(root.getState());
            root.release();
            root = copy;
        }
        assertFalse(root.shouldBeFlushed()); // should still have a custom flush threshold
        root.release();
    }

    @Test
    @DisplayName("Copy of a root node with terminated pipeline")
    void copyOfRootNodeWithTerminatedPipeline() {
        VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.getPipeline().terminate();
        assertThrows(IllegalStateException.class, () -> root.copy());
    }

    @Test
    @DisplayName("Calculate hashes for persisted leaf nodes")
    void testFullRehash() throws InterruptedException {
        final VirtualRootNode<TestKey, TestValue> root = prepareRootForFullRehash();

        root.fullLeafRehashIfNecessary();

        // make sure that the elements have hashes
        IntStream.range(1, 101).forEach(index -> {
            assertNotNull(root.getRecords().findHash(index));
        });
    }

    @Test
    @DisplayName("Root node should be hashed after full leaves rehash")
    void testHashedAfterFullRehash() {
        final VirtualRootNode<TestKey, TestValue> root = prepareRootForFullRehash();
        root.fullLeafRehashIfNecessary();

        assertTrue(root.isHashed());
    }

    @Test
    @DisplayName("Fail to do full rehash because of save failure")
    void testFullRehash_failOnSave() throws InterruptedException {
        final VirtualRootNode<TestKey, TestValue> root = prepareRootForFullRehash();
        ((InMemoryDataSource) root.getDataSource()).setFailureOnSave(true);

        assertThrows(MerkleSynchronizationException.class, () -> root.fullLeafRehashIfNecessary());
    }

    @Test
    @DisplayName("Fail to do full rehash because of load failure")
    void testFullRehash_failOnLeafLookup() throws InterruptedException {
        final VirtualRootNode<TestKey, TestValue> root = prepareRootForFullRehash();
        ((InMemoryDataSource) root.getDataSource()).setFailureOnLeafRecordLookup(true);

        assertThrows(MerkleSynchronizationException.class, () -> root.fullLeafRehashIfNecessary());
    }

    @Test
    @DisplayName("Fail to do full rehash because of hash lookup failure")
    void testFullRehash_failOnHashLookup() throws InterruptedException {
        final VirtualRootNode<TestKey, TestValue> root = prepareRootForFullRehash();
        ((InMemoryDataSource) root.getDataSource()).setFailureOnHashLookup(true);

        assertThrows(UncheckedIOException.class, () -> root.fullLeafRehashIfNecessary());
    }

    private static VirtualRootNode<TestKey, TestValue> prepareRootForFullRehash() {
        final VirtualRootNode<TestKey, TestValue> root = createRoot();
        root.enableFlush();

        // add 100 elements
        IntStream.range(1, 101).forEach(index -> {
            root.put(new TestKey(index), new TestValue(nextInt()));
        });

        // make sure that the elements have no hashes
        IntStream.range(1, 101).forEach(index -> {
            assertNull(root.getRecords().findHash(index));
        });

        // prepare the root for h full leaf rehash
        root.setImmutable(true);
        root.getCache().seal();
        root.flush();

        return root;
    }

    @Test
    void getVersion() {
        assertEquals(3, createRoot().getVersion());
    }

    @Test
    void postInitNoOpIfLearnerTreeViewIsSet() {
        VirtualRootNode<TestKey, TestValue> root = createRoot();
        VirtualRootNode<TestKey, TestValue> anotherRoot = createRoot();
        anotherRoot.computeHash();
        root.setupWithOriginalNode(anotherRoot);
        assertDoesNotThrow(() -> root.postInit(null));
    }
}
