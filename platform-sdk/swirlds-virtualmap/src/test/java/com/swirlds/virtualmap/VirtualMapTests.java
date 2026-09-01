// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_VIRTUAL_MAP_CONFIG;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.assertVmsAreEqual;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.file.FileUtils.deleteDirectory;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.hiero.base.utility.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryDataSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"DataFlowIssue", "unchecked"})
class VirtualMapTests extends VirtualTestBase {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setupNonNOPLogger() throws FileNotFoundException {
        // use actual log4j logger, and not the NOP loader.
        loadLog4jContext();
    }

    /*
     * Test a fresh map
     **/

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Fresh")})
    @DisplayName("A fresh map is mutable")
    void freshMapIsMutable() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertEquals(1, vm.size(), "VirtualMap size is wrong");
        vm.release();
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Fresh")})
    @DisplayName("A fresh map returns a non-null data source")
    void freshMapHasDataSource() {
        final VirtualMap vm = createMap();
        assertNotNull(vm.getDataSource(), "Unexpected null data source");
        vm.release();
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("Fresh")})
    @DisplayName("The root node of an empty tree has no children")
    void vmStateAddedWithThefFirstChild() {
        final VirtualMap vm = createMap();
        assertTrue(vm.isEmpty());

        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertFalse(vm.isEmpty());
        assertEquals(1, vm.size(), "Unexpected size");

        vm.release();
    }

    /*
     * Test the fast copy implementation
     **/

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("FastCopy")})
    @DisplayName("Original after copy is immutable")
    void originalAfterCopyIsImmutable() {
        final VirtualMap vm = createMap();
        final VirtualMap copy = vm.copy();
        assertTrue(vm.isImmutable(), "Copied VirtualMap should have been immutable");
        assertFalse(copy.isImmutable(), "Most recent VirtualMap should have been mutable");
        vm.release();
        copy.release();
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("FastCopy")})
    @DisplayName("Cannot copy twice")
    void cannotCopyTwice() {
        final VirtualMap vm = createMap();
        final VirtualMap copy = vm.copy();
        assertThrows(MutabilityException.class, vm::copy, "Calling copy twice should have thrown exception");
        vm.release();
        copy.release();
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("FastCopy")})
    @DisplayName("Cannot copy a released fcm")
    void cannotCopyAReleasedMap() {
        final VirtualMap vm = createMap();
        vm.release();
        assertThrows(ReferenceCountException.class, vm::copy, "Calling copy after release should throw");
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("FastCopy")})
    @DisplayName("Original is not impacted by changes to modified copy")
    void originalIsUnaffectedWhenModifyingCopy() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        vm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);

        // Perform some combination of add, remove, replace and leaving alone
        final VirtualMap copy = vm.copy();
        assertNotNull(copy.get(A_KEY, TestValueCodec.INSTANCE), "Entry for A_KEY not found");
        copy.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        copy.remove(C_KEY, TestValueCodec.INSTANCE);
        copy.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        copy.put(E_KEY, EMU, TestValueCodec.INSTANCE);

        assertEquals(APPLE, vm.get(A_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(BANANA, vm.get(B_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(CHERRY, vm.get(C_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(3, vm.size(), "Unexpected size");

        assertEquals(AARDVARK, copy.get(A_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(BANANA, copy.get(B_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(DOG, copy.get(D_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(EMU, copy.get(E_KEY, TestValueCodec.INSTANCE), "Unexpected value");
        assertEquals(4, copy.size(), "Unexpected size");
        vm.release();
        copy.release();
    }

    @Test
    @DisplayName("Cannot detach mutable copy")
    void unableDetachFromMutableCopy() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);

        try {
            assertThrows(IllegalStateException.class, vm::detach, "Can't detach mutable copy");
        } finally {
            vm.release();
        }
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("FastCopy")})
    @DisplayName("Detached is not impacted by changes to original map copy")
    void detachedIsUnaffectedWhenModifyingCopy() throws IOException {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        vm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);

        VirtualMap copy = vm.copy(); // make immutable and copy
        vm.getHash();
        final RecordAccessor detached = vm.detach();

        try {
            // Perform some combination of add, remove, replace and leaving alone
            copy.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
            copy.remove(C_KEY, TestValueCodec.INSTANCE);
            copy.put(D_KEY, DOG, TestValueCodec.INSTANCE);
            copy.put(E_KEY, EMU, TestValueCodec.INSTANCE);

            // verify detached is not changed
            VirtualLeafBytes<TestValue> leaf;

            leaf = detached.findLeafRecord(A_KEY);
            assertNotNull(leaf);
            assertEquals(APPLE, leaf.value(TestValueCodec.INSTANCE, Codec.DEFAULT_MAX_SIZE));

            leaf = detached.findLeafRecord(B_KEY);
            assertNotNull(leaf);
            assertEquals(BANANA, leaf.value(TestValueCodec.INSTANCE, Codec.DEFAULT_MAX_SIZE));

            leaf = detached.findLeafRecord(C_KEY);
            assertNotNull(leaf);
            assertEquals(CHERRY, leaf.value(TestValueCodec.INSTANCE, Codec.DEFAULT_MAX_SIZE));

            assertNull(detached.findLeafRecord(D_KEY));
            assertNull(detached.findLeafRecord(E_KEY));
        } finally {
            vm.release();
            copy.release();
            detached.close();
        }
    }

    /*
     * Test the map-like implementation
     **/

    @Test
    @DisplayName("Size matches number of items input")
    void sizeMatchesNumberOfItemsInput() {
        final VirtualMap vm = createMap();
        assertEquals(0, vm.size(), "Unexpected size");

        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertEquals(1, vm.size(), "Unexpected size");

        // Add a couple more elements
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        assertEquals(2, vm.size(), "Unexpected size");
        vm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        assertEquals(3, vm.size(), "Unexpected size");

        // replace a couple elements (out of order even!)
        assertNotNull(vm.get(B_KEY, TestValueCodec.INSTANCE), "Entry for B_KEY not found");
        vm.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        assertNotNull(vm.get(A_KEY, TestValueCodec.INSTANCE), "Entry for A_KEY not found");
        vm.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        assertEquals(3, vm.size(), "Unexpected size");

        // Loop and add a million items and make sure the size is matching
        for (int i = 1000; i < 1_001_000; i++) {
            vm.put(TestKey.longToKey(i), new TestValue("value" + i), TestValueCodec.INSTANCE);
        }

        assertEquals(1_000_003, vm.size(), "Unexpected size");
        vm.release();
    }

    @Test
    @DisplayName("Get of null key throws exception")
    void getOfNullKeyThrowsException() {
        final VirtualMap vm = createMap();
        assertThrows(
                NullPointerException.class, () -> vm.get(null, TestValueCodec.INSTANCE), "Null keys are not allowed");
        vm.release();
    }

    @Test
    @DisplayName("Get of missing key returns null")
    void getOfMissingKeyReturnsNull() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);

        assertNull(vm.get(C_KEY, TestValueCodec.INSTANCE), "Expected no value");
        assertNull(vm.getBytes(C_KEY), "Expected no value");
        vm.release();
    }

    @Test
    @DisplayName("Get of key returns value")
    void getOfKeyReturnsValue() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        assertEquals(APPLE, vm.get(A_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(BANANA, vm.get(B_KEY, TestValueCodec.INSTANCE), "Wrong value");

        vm.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        assertEquals(AARDVARK, vm.get(A_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(BANANA, vm.get(B_KEY, TestValueCodec.INSTANCE), "Wrong value");
        vm.release();
    }

    @Test
    @DisplayName("Put with null key throws exception")
    void putWithNullKeyThrowsException() {
        final VirtualMap vm = createMap();
        assertThrows(
                NullPointerException.class,
                () -> vm.put(null, BANANA, TestValueCodec.INSTANCE),
                "Null keys are not allowed");

        vm.release();
    }

    @Test
    @DisplayName("Put with null values are allowed")
    void putWithNullValuesAreAllowed() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, null, TestValueCodec.INSTANCE);
        assertNull(vm.get(A_KEY, TestValueCodec.INSTANCE), "Expected null");
        vm.release();
    }

    @Test
    @DisplayName("Multiple keys can have the same value")
    void manyKeysCanHaveTheSameValue() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, null, TestValueCodec.INSTANCE);
        vm.put(B_KEY, null, TestValueCodec.INSTANCE);
        vm.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        vm.put(D_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);

        assertNull(vm.get(A_KEY, TestValueCodec.INSTANCE), "Expected null");
        assertNull(vm.get(B_KEY, TestValueCodec.INSTANCE), "Expected null");
        assertEquals(CUTTLEFISH, vm.get(C_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(CUTTLEFISH, vm.get(D_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(4, vm.size(), "Wrong size");
        vm.release();
    }

    @Test
    @DisplayName("Put many and get many")
    void putManyAndGetMany() {
        final VirtualMap vm = createMap();
        for (int i = 0; i < 1000; i++) {
            vm.put(TestKey.longToKey(i), new TestValue("value" + i), TestValueCodec.INSTANCE);
        }

        for (int i = 0; i < 1000; i++) {
            assertEquals(
                    new TestValue("value" + i), vm.get(TestKey.longToKey(i), TestValueCodec.INSTANCE), "Wrong value");
        }

        vm.release();
    }

    @Test
    @DisplayName("Replace many and get many")
    void replaceManyAndGetMany() {
        final VirtualMap original = createMap();
        for (int i = 0; i < 1000; i++) {
            original.put(TestKey.longToKey(i), new TestValue("value" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap fcm = original.copy();
        for (int i = 1000; i < 2000; i++) {
            final Bytes key = TestKey.longToKey(i - 1000);
            // Replace is get + put
            assertNotNull(fcm.get(key, TestValueCodec.INSTANCE), "Value for key=" + key + "is not found");
            fcm.put(key, new TestValue("value" + i), TestValueCodec.INSTANCE);
        }

        for (int i = 1000; i < 2000; i++) {
            assertEquals(
                    new TestValue("value" + i),
                    fcm.get(TestKey.longToKey((i - 1000)), TestValueCodec.INSTANCE),
                    "Wrong value");
        }

        original.release();
        fcm.release();
    }

    @Test
    @DisplayName("Remove from an empty map")
    void removeEmptyMap() {
        final VirtualMap vm = createMap();
        assertNull(vm.remove(A_KEY, TestValueCodec.INSTANCE), "Expected null");
        vm.release();
    }

    @Test
    @DisplayName("Test of isEmpty and size")
    void testIsEmptyAndSize() {
        final VirtualMap vm = createMap();

        assertEquals(0, vm.size());
        assertTrue(vm.isEmpty());

        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        assertEquals(1, vm.size()); // VM state is included
        assertFalse(vm.isEmpty());
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        assertEquals(2, vm.size());
        assertFalse(vm.isEmpty());
        vm.remove(B_KEY, TestValueCodec.INSTANCE);
        assertEquals(1, vm.size());
        assertFalse(vm.isEmpty());

        vm.remove(A_KEY, TestValueCodec.INSTANCE);

        assertEquals(0, vm.size());
        assertTrue(vm.isEmpty());

        vm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        assertFalse(vm.isEmpty());

        vm.release();
    }

    @Test
    @DisplayName("Add a value and then remove it immediately")
    void removeValueJustAdded() {
        VirtualMap fcm = createMap();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        assertEquals(APPLE, fcm.remove(A_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(BANANA, fcm.remove(B_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(CHERRY, fcm.remove(C_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(DATE, fcm.remove(D_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(EGGPLANT, fcm.remove(E_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(FIG, fcm.remove(F_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(GRAPE, fcm.remove(G_KEY, TestValueCodec.INSTANCE), "Wrong value");

        // FUTURE WORK validate hashing works as expected

        fcm.release();
    }

    @Test
    @DisplayName("Add a value that had just been removed")
    void addValueJustRemoved() {
        VirtualMap fcm = createMap();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        assertEquals(APPLE, fcm.remove(A_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(BANANA, fcm.remove(B_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(CHERRY, fcm.remove(C_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(DATE, fcm.remove(D_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(EGGPLANT, fcm.remove(E_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(FIG, fcm.remove(F_KEY, TestValueCodec.INSTANCE), "Wrong value");
        assertEquals(GRAPE, fcm.remove(G_KEY, TestValueCodec.INSTANCE), "Wrong value");

        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        // FUTURE WORK validate hashing works as expected

        fcm.release();
    }

    /*
     * Test various copy and termination scenarios to verify pipeline behavior
     **/

    @Test
    @Tags({@Tag("VirtualMap"), @Tag("Pipeline"), @Tag("VMAP-021")})
    @DisplayName("Database is closed after all copies are released")
    void databaseClosedAfterAllCopiesAreReleased() throws InterruptedException {
        final VirtualMap copy0 = createMap();
        final InMemoryDataSource ds = (InMemoryDataSource) copy0.getDataSource();
        final VirtualMap copy1 = copy0.copy();
        final VirtualMap copy2 = copy1.copy();
        final VirtualMap copy3 = copy2.copy();
        final VirtualMap copy4 = copy3.copy();

        assertFalse(ds.isClosed(), "Should not be closed yet");
        copy0.release();
        assertFalse(ds.isClosed(), "Should not be closed yet");
        copy1.release();
        assertFalse(ds.isClosed(), "Should not be closed yet");
        copy2.release();
        assertFalse(ds.isClosed(), "Should not be closed yet");
        copy3.release();
        assertFalse(ds.isClosed(), "Should not be closed yet");
        copy4.release();

        assertTrue(copy0.waitUntilFamilyDestroyed(Duration.ofSeconds(3)), "Map family should be destroyed");
        assertTrue(ds.isClosed(), "Should now be released");
    }

    @Test
    @DisplayName("Hashed maps have non-null hashes on everything")
    void nonNullHashesOnHashedMap() {
        VirtualMap fcm = createMap();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap completed = fcm;
        fcm = fcm.copy();
        completed.getHash(); // calculate hash

        assertMapIsFullyHashed(completed);

        completed.release();
        fcm.release();
    }

    private static void assertMapIsFullyHashed(VirtualMap completed) {
        for (int i = 1; i <= completed.getMetadata().getLastLeafPath(); i++) {
            assertNotNull(completed.getRecords().findHash(i));
        }
    }

    @Test
    @DisplayName("Million sized hashed maps have non-null hashes on everything")
    void millionNonNullHashesOnHashedMap() {
        VirtualMap fcm = createMap();
        for (int i = 0; i < 1_000_000; i++) {
            fcm.put(TestKey.longToKey(i), new TestValue("" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap completed = fcm;
        fcm = fcm.copy();

        try {
            final Hash firstHash = completed.getHash();
            assertMapIsFullyHashed(completed);

            final Random rand = new Random(1234);
            for (int i = 0; i < 10_000; i++) {
                final int index = rand.nextInt(1_000_000);
                final int value = 1_000_000 + rand.nextInt(1_000_000);
                fcm.put(TestKey.longToKey(index), new TestValue("" + value), TestValueCodec.INSTANCE);
            }

            final VirtualMap second = fcm;
            fcm = copyAndRelease(fcm);
            final Hash secondHash = second.getHash();
            assertNotSame(firstHash, secondHash, "Wrong value");
        } finally {
            fcm.release();
            completed.release();
        }
    }

    @Test
    @DisplayName("put should not mutate old copies")
    void checkPutMutation() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        final TestValue value = vm.get(A_KEY, TestValueCodec.INSTANCE);

        final VirtualMap vm2 = vm.copy();
        vm2.put(A_KEY, new TestValue("Mutant2"), TestValueCodec.INSTANCE);
        final TestValue value2 = vm2.get(A_KEY, TestValueCodec.INSTANCE);

        final TestValue value3 = vm.get(A_KEY, TestValueCodec.INSTANCE);

        assertEquals("Mutant2", value2.getValue());
        assertEquals("Apple", value3.getValue());
        assertEquals("Apple", value.getValue());
    }

    @Test(/* no exception expected */ )
    @DisplayName("Partly dirty maps have missing hashes only on dirty leaves and parents")
    void nullHashesOnDirtyNodes() {
        VirtualMap fcm = createMap();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        fcm = copyAndRelease(fcm);

        // Both of these are on different parents, but the same grandparent.
        assertNotNull(fcm.get(D_KEY, TestValueCodec.INSTANCE));
        fcm.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BEAR, TestValueCodec.INSTANCE);

        // This hash iterator should visit MapState, B, <internal>, D, <internal>, <internal (root)>, fcm
        // FUTURE WORK gotta figure out how to test
        //        final var hashItr = new MerkleHashIterator(fcm);
        //        hashItr.next();
        //        assertEquals(new VFCLeafNode<>(B_KEY, BEAR), getRecordFromNode((MerkleLeaf) hashItr.next()));
        //        hashItr.next();
        //        assertEquals(new VFCLeafNode<>(D_KEY, DOG), getRecordFromNode((MerkleLeaf) hashItr.next()));
        //        hashItr.next();
        //        hashItr.next();
        //        assertEquals(fcm, hashItr.next());
        //        assertFalse(hashItr.hasNext());

        fcm.release();
    }

    @Test
    void testAsyncHashing() {
        VirtualMap fcm = createMap();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        VirtualMap completed = fcm;
        fcm = fcm.copy();
        final Hash expectedHash = completed.getHash();

        VirtualMap fcm2 = createMap();
        fcm2.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm2.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm2.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm2.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm2.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm2.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm2.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        completed.release();
        completed = fcm2;
        fcm2 = fcm2.copy();
        final Hash actualHash = completed.getHash();
        assertEquals(expectedHash, actualHash, "Wrong value");

        fcm.release();
        fcm2.release();
        completed.release();
    }

    /**
     * Make a copy of a map and release the original.
     */
    private VirtualMap copyAndRelease(final VirtualMap original) {
        final VirtualMap copy = original.copy();
        original.release();
        return copy;
    }

    /*
     * Test statistics on a fresh map
     **/

    /**
     * Bug #4233 was caused by an NPE when flushing a copy that had been released.
     * This happened because the detach for state saving does not
     * result in the detached state having a data source.
     */
    @Test
    void canFlushCopy() throws InterruptedException {
        final VirtualMap map0 = createMap();
        map0.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        map0.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DATE, TestValueCodec.INSTANCE);

        final VirtualMap map1 = map0.copy();
        map1.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        map1.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        map1.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap map2 = map1.copy();

        assertNotNull(map1.getHash(), "Hash should have been produced for map1");

        map1.enableFlush();
        map0.release();

        map1.release();
        final CountDownLatch finishedFlushing = new CountDownLatch(1);
        final Thread th = new Thread(() -> {
            try {
                map1.waitUntilFlushed();
                finishedFlushing.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Timed out waiting for flush");
            }
        });
        th.start();

        try {
            if (!finishedFlushing.await(4, SECONDS)) {
                th.interrupt();
                fail("Timed out, which happens if the test fails or the test has a bug but never if it passes");
            }
        } finally {
            map2.release();
        }
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Tests nodeCacheSizeB metric")
    void testNodeCacheSizeMetric() throws InterruptedException {
        final MetricsConfig metricsConfig = DEFAULT_CONFIGURATION.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);

        VirtualMap map0 = createMap();
        map0.registerMetrics(metrics);

        // createMap() creates a map labelled "state"
        Metric metric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_lifecycle_nodeCacheSizeB_state");
        assertNotNull(metric);
        if (!(metric instanceof LongGauge)) {
            throw new AssertionError("nodeCacheSizeMb metric is not a gauge");
        }

        final long metricValue = (long) metric.get(ValueType.VALUE);
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 50; j++) {
                map0.put(
                        TestKey.longToKey((char) (i * 50 + j)),
                        new TestValue(String.valueOf(i * j + 1)),
                        TestValueCodec.INSTANCE);
            }

            VirtualMap map1 = map0.copy();
            map0.release();
            map0 = map1;

            assertEventuallyTrue(
                    () -> (long) metric.get(ValueType.VALUE) > metricValue,
                    Duration.ofMillis(1000),
                    "Node cache size must increase");
        }

        final long value = (long) metric.get(ValueType.VALUE);;

        final VirtualMap lastMap = map0;
        lastMap.enableFlush();
        VirtualMap map1 = map0.copy();
        map0.release();
        lastMap.waitUntilFlushed();
        map1.release();

        assertEventuallyTrue(
                () -> (long) metric.get(ValueType.VALUE) < value,
                Duration.ofMillis(1000),
                "Node cache size must decrease after flush");
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Tests vMapFlushes metric")
    void testFlushCount() throws InterruptedException {
        final MetricsConfig metricsConfig = DEFAULT_CONFIGURATION.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);

        VirtualMap map0 = createMap();
        map0.registerMetrics(metrics);

        long flushCount = 0;
        final int totalCount = 1000;
        for (int i = 0; i < totalCount; i++) {
            VirtualMap map1 = map0.copy();
            map0.release();
            // shouldBeFlushed() can only be called on a released instance
            if (map0.shouldBeFlushed()) {
                flushCount++;
            }
            map0 = map1;

            // Make sure at least some maps need to be flushed, including the last one
            if ((i % 57 == 0) || (i == totalCount - 1)) {
                map1.enableFlush();
            }
        }

        // Don't release the last map yet, as it would terminate the pipeline. Make a copy first,
        // release the map, then wait for the root to be flushed, then release the copy
        VirtualMap map1 = map0.copy();
        map0.release();
        // shouldBeFlushed() can only be called on a released instance
        if (map0.shouldBeFlushed()) {
            flushCount++;
        }
        map0.waitUntilFlushed();
        map1.release();

        // createMap() creates a map labelled "state"
        Metric metric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_lifecycle_flushCount_state");
        assertNotNull(metric);
        if (!(metric instanceof Counter counterMetric)) {
            throw new AssertionError("flushCount metric is not a counter");
        }
        // There is a potential race condition here, as we release `VirtualMap.flushLatch`
        // before we update the statistics (see https://github.com/hashgraph/hedera-services/issues/8439)
        assertEventuallyEquals(
                flushCount,
                counterMetric::get,
                Duration.ofSeconds(4),
                "Expected flush count (%s) to match actual value (%s)".formatted(flushCount, counterMetric.get()));
    }

    /*
     * Test serialization and deserialization
     **/

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("A copied map is serializable and then deserializable")
    void testExternalSerializationAndDeserialization() throws IOException {
        final VirtualMap map0 = createMap();
        map0.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        map0.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        map0.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        map0.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap map1 = map0.copy(); // this should make map0 immutable
        assertNotNull(map0.getHash(), "Hash should have been produced for map0");
        assertTrue(map0.isImmutable(), "Copied VirtualMap should have been immutable");
        assertVmsAreEqual(map0, map1);
        // serialize the existing maps
        map0.createSnapshot(testDirectory);

        final VirtualMap map2 =
                VirtualMap.loadFromDirectory(testDirectory, DEFAULT_CONFIGURATION, InMemoryBuilder::new);
        assertVmsAreEqual(map0, map2);

        // release the maps and clean up the temporary directory
        map0.release();
        map1.release();
        map2.release();
        deleteDirectory(testDirectory);
    }

    /*
     * Test some bigger scenarios
     **/

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VMAP-019")})
    @DisplayName("Insert one million elements with same key but different value")
    void insertRemoveAndModifyOneMillion() throws InterruptedException {
        final int changesPerBatch = 15_432; // Some unexpected size just to be crazy
        final int max = 1_000_000;
        VirtualMap map = createMap();
        try {
            for (int i = 0; i < max; i++) {
                if (i > 0 && i % changesPerBatch == 0) {
                    VirtualMap older = map;
                    map = map.copy();
                    older.release();
                }

                map.put(TestKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
            }

            for (int i = 0; i < max; i++) {
                assertEquals(new TestValue(i), map.get(TestKey.longToKey(i), TestValueCodec.INSTANCE), "Expected same");
            }

            for (int i = 0; i < max; i++) {
                if (i > 0 && i % changesPerBatch == 0) {
                    VirtualMap older = map;
                    map = map.copy();
                    older.release();
                }

                map.remove(TestKey.longToKey(i));
            }

            assertEquals(0, map.size(), "All elements should have been removed");

            for (int i = 0; i < max; i++) {
                if (i > 0 && i % changesPerBatch == 0) {
                    VirtualMap older = map;
                    map = map.copy();
                    older.release();
                }

                map.put(TestKey.longToKey(i + max), new TestValue(i + max), TestValueCodec.INSTANCE);
            }

            for (int i = 0; i < max; i++) {
                assertEquals(
                        new TestValue(i + max),
                        map.get(TestKey.longToKey(i + max), TestValueCodec.INSTANCE),
                        "Expected same");
                assertNull(
                        map.get(TestKey.longToKey(i), TestValueCodec.INSTANCE),
                        "The old value should not exist anymore");
                assertNull(map.getBytes(TestKey.longToKey(i)), "The old value should not exist anymore");
            }
        } finally {
            map.release();
        }
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Delete a value that was moved to a different virtual path")
    void deletedObjectLeavesOnFlush() throws InterruptedException {
        VirtualMap map = createMap();
        for (int i = 0; i < 8; i++) {
            map.put(TestKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
        }

        map.enableFlush();

        RecordAccessor records = map.getRecords();
        // Check that key/value 0 is at path 7
        VirtualLeafBytes<TestValue> leaf = records.findLeafRecord(8);
        assertNotNull(leaf);
        assertEquals(TestKey.longToKey(4), leaf.keyBytes());
        assertEquals(new TestValue(4).toBytes(), leaf.valueBytes());
        assertEquals(new TestValue(4), leaf.value(TestValueCodec.INSTANCE, Codec.DEFAULT_MAX_SIZE));

        VirtualMap copy = map.copy();
        map.release();
        map.waitUntilFlushed();
        map = copy;

        // Move key/value to a different path, then delete
        map.remove(TestKey.longToKey(0));
        map.remove(TestKey.longToKey(2));
        map.put(TestKey.longToKey(8), new TestValue(8), TestValueCodec.INSTANCE);
        map.put(TestKey.longToKey(0), new TestValue(0), TestValueCodec.INSTANCE);
        map.remove(TestKey.longToKey(0));

        map.enableFlush();

        copy = map.copy();
        map.release();
        map.waitUntilFlushed();
        map = copy;

        // During this second flush, key/value 0 must be deleted from the map despite it's
        // path the virtual tree doesn't match the path in the data source
        assertFalse(map.containsKey(TestKey.longToKey(0)));
        assertNull(map.get(TestKey.longToKey(0), TestValueCodec.INSTANCE));
        assertNull(map.getBytes(TestKey.longToKey(0)));

        map.release();
    }

    @Test
    void testEnableVirtualRootFlush() {
        VirtualMap fcm0 = createMap();
        assertFalse(fcm0.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm1 = fcm0.copy();
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm2 = fcm1.copy();
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm3 = fcm2.copy();
        fcm3.enableFlush();
        assertTrue(fcm3.shouldBeFlushed(), "map should now be flushed");

        fcm0.release();
        fcm1.release();
        fcm2.release();
        fcm3.release();
    }

    @Test
    @DisplayName("If there are no dirty leaves, previous copy's root hash is used")
    void emptyDirtyLeavesResultInHashFromPreviousCopy() throws InterruptedException {
        final VirtualDataSourceBuilder builder = new InMemoryBuilder();

        final VirtualMap vm = new VirtualMap(builder, DEFAULT_CONFIGURATION);
        vm.enableFlush();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);

        final VirtualMap copy = vm.copy();
        copy.enableFlush();
        vm.release();
        // Hash the copy and flush all data to disk, including the root hash
        vm.waitUntilFlushed();
        final Hash expectedHash = vm.getHash();

        final VirtualMap copy2 = copy.copy();
        copy.release();
        copy.waitUntilFlushed();

        assertEquals(expectedHash, copy2.getHash(), "hash should match expected");

        copy2.release();
    }

    @Test
    @DisplayName("Remove only element")
    void removeOnlyElement() throws InterruptedException {

        final VirtualMap fcm = createMap();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);

        final VirtualMap copy = fcm.copy();
        fcm.release();
        fcm.waitUntilFlushed();

        final TestValue removed = copy.remove(A_KEY, TestValueCodec.INSTANCE);
        assertEquals(APPLE, removed, "Wrong value");

        // FUTURE WORK validate hashing works as expected

        copy.release();
    }

    @Test
    @DisplayName("Remove element twice")
    void removeElementTwice() throws InterruptedException {
        final VirtualMap fcm = createMap();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);

        final VirtualMap copy = fcm.copy();
        fcm.release();
        fcm.waitUntilFlushed();

        final TestValue removed = copy.remove(B_KEY, TestValueCodec.INSTANCE);
        final TestValue removed2 = copy.remove(B_KEY, TestValueCodec.INSTANCE);
        assertEquals(BANANA, removed, "Wrong value");
        assertNull(removed2, "Expected null");
        copy.release();
    }

    @Test
    @DisplayName("Remove elements in reverse order")
    void removeInReverseOrder() throws InterruptedException {
        final VirtualMap fcm = createMap();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        fcm.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        fcm.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        fcm.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        fcm.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap copy = fcm.copy();
        fcm.release();
        fcm.waitUntilFlushed();

        assertEquals(GRAPE, copy.remove(G_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, F_KEY, B_KEY, D_KEY);
        assertEquals(FIG, copy.remove(F_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, B_KEY, D_KEY);
        assertEquals(EGGPLANT, copy.remove(E_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, C_KEY, B_KEY, D_KEY);
        assertEquals(DATE, copy.remove(D_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, C_KEY, B_KEY);
        assertEquals(CHERRY, copy.remove(C_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY, B_KEY);
        assertEquals(BANANA, copy.remove(B_KEY, TestValueCodec.INSTANCE), "Wrong value");
        //        assertLeafOrder(fcm, A_KEY);
        assertEquals(APPLE, copy.remove(A_KEY, TestValueCodec.INSTANCE), "Wrong value");

        // FUTURE WORK validate hashing works as expected

        copy.release();
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
        final VirtualMap root1 = createMap();
        for (int index = 0; index < totalSize; index++) {
            final Bytes key = TestKey.longToKey(index);
            final TestValue value = new TestValue(index);
            root1.put(key, value, TestValueCodec.INSTANCE);
        }

        final VirtualMap root2 = createMap();
        final long firstLeafPath = root1.getMetadata().getFirstLeafPath();
        final long lastLeafPath = root1.getMetadata().getLastLeafPath();
        for (long index = firstLeafPath; index <= lastLeafPath; index++) {
            final VirtualLeafBytes leaf = root1.getRecords().findLeafRecord(index);
            final Bytes key = leaf.keyBytes().replicate();
            final Bytes value = leaf.valueBytes().replicate();
            root2.putBytes(key, value);
        }

        for (int index = 0; index < totalSize; index++) {
            final Bytes key = TestKey.longToKey(index);
            root1.remove(key);
        }

        assertEquals(0, root1.size(), "All elements should have been removed");
        root1.release();
        TimeUnit.MILLISECONDS.sleep(100);
        System.gc();
        assertEquals(totalSize, root2.size(), "New map is expected to have all data and VirtualMap.Metadata");
        for (int index = 0; index < totalSize; index++) {
            final Bytes key = TestKey.longToKey(index);
            final TestValue expectedValue = new TestValue(index);
            final TestValue value = root2.get(key, TestValueCodec.INSTANCE);
            assertEquals(expectedValue, value, "Values have the same content");
        }
    }

    @Test
    @DisplayName("Snapshot and restore")
    void snapshotAndRestore() throws IOException {
        final VirtualDataSourceBuilder dsBuilder = new InMemoryBuilder();
        final List<VirtualMap> copies = new LinkedList<>();
        final VirtualMap copy0 = new VirtualMap(dsBuilder, DEFAULT_CONFIGURATION);
        copies.add(copy0);
        for (int i = 1; i <= 10; i++) {
            final VirtualMap prevCopy = copies.get(i - 1);
            final VirtualMap copy = prevCopy.copy();
            // i-th copy contains TestKey(i)
            copy.put(TestKey.longToKey(i), new TestValue(i + 100), TestValueCodec.INSTANCE);
            copies.add(copy);
        }
        for (VirtualMap copy : copies) {
            // Force virtual map / root node hashing
            copy.getHash();
        }
        // Take a snapshot of copy 5
        final VirtualMap copy5 = copies.get(5);
        final Path snapshotPath = new TestFileSystemManager(testDirectory).resolveNewTemp("snapshotAndRestore");
        Files.createDirectories(snapshotPath);
        copy5.createSnapshot(snapshotPath);
        try {
            final VirtualMap restored =
                    VirtualMap.loadFromDirectory(snapshotPath, DEFAULT_CONFIGURATION, InMemoryBuilder::new);
            // All keys 1 to 5 should be in the snapshot
            for (int i = 1; i < 6; i++) {
                final Bytes key = TestKey.longToKey(i);
                assertTrue(restored.containsKey(key), "Key " + i + " not found");
                assertEquals(new TestValue(i + 100), restored.get(key, TestValueCodec.INSTANCE));
            }
            // All keys 6 to 10 should not be there
            for (int i = 6; i < 10; i++) {
                final Bytes key = TestKey.longToKey(i);
                assertFalse(restored.containsKey(key), "Key " + i + " found");
                assertNull(restored.get(key, TestValueCodec.INSTANCE));
            }

        } finally {
            copies.forEach(VirtualMap::release);
        }
    }

    @Test
    @DisplayName("Detach is not affected when map destroyed")
    void detachIsNotAffectedByMapDestroy() throws IOException, InterruptedException {
        final VirtualMap original = new VirtualMap(new InMemoryBuilder(), DEFAULT_CONFIGURATION);
        Bytes testKey = Bytes.wrap("testKey");
        original.put(testKey, new TestValue("testValue"), TestValueCodec.INSTANCE);
        final VirtualMap copy = original.copy();

        original.getHash(); // forces copy to become hashed

        final RecordAccessor detachedCopy = original.detach();
        assertNotNull(detachedCopy);

        // release maps family
        original.release();
        copy.release();

        try {
            assertTrue(original.waitUntilFamilyDestroyed(Duration.ofSeconds(3)), "Map family should be destroyed");

            VirtualLeafBytes<?> leafRecord = detachedCopy.findLeafRecord(1L);
            assertNotNull(leafRecord);
            assertEquals(testKey, leafRecord.keyBytes(), "Path does not match");
        } finally {
            detachedCopy.close();
        }
    }

    @Test
    @DisplayName("Default flush threshold not zero")
    void defaultFlushThresholdTest() {
        VirtualMap root = createMap();
        assertEquals(DEFAULT_VIRTUAL_MAP_CONFIG.copyFlushCandidateThreshold(), root.getFlushCandidateThreshold());
        root.release();
    }

    @Test
    @DisplayName("Flush threshold is inherited by copies")
    void flushThresholdInheritedTest() {
        final long threshold = 12345678L;
        VirtualMap root = createMap();
        root.setFlushCandidateThreshold(threshold);
        for (int i = 0; i < 50; i++) {
            assertEquals(threshold, root.getFlushCandidateThreshold());
            VirtualMap copy = root.copy();
            root.release();
            root = copy;
        }
        root.release();
    }

    @Test
    void getVersion() {
        assertEquals(4, createMap().getVersion());
    }

    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VirtualNodeCache"), @Tag("Leaf")})
    @DisplayName("deletedLeaves()")
    void deletedLeaves() {
        // CREATED followed by UPDATED, UPDATED+DELETED, DELETED
        // CREATED+UPDATED followed by UPDATED, UPDATED+DELETED, DELETED
        // UPDATED followed by UPDATED, UPDATED+DELETED, DELETED
        // DELETED followed by CREATED, CREATED+UPDATED, CREATED+DELETED, CREATED+UPDATED+DELETED, DELETED (nop)

        // Create the following chain of mutations:
        // A: [D, v2] -> [U+D (AARDVARK), v1] -> [C (APPLE), v0]
        // B: [D, v3] -> [C+U (BEAR, BLASTOFF), v2] -> [D, v1] -> [C (BANANA), v0]
        // C: [C+U+D (CHEMISTRY, CHAD), v3] -> [D, v2] -> [U (COMET), v1] -> [C+U (CHERRY, CUTTLEFISH), v0]
        // D: [C+U (DISCIPLINE, DENMARK), v2] -> [U+D (DRACO), v1] -> [C+U (DATE, DOG), v0]
        // E: [C+U (EXOPLANET, ECOLOGY), v3] -> [D, v2] -> [C+U (EGGPLANT, EMU), v0]
        // F: [C (FORCE), v3] -> [D, v2] -> [U (FOX), v1] -> [C (FIG), v0]
        // G: [U (GRAVITY), v3] -> [U (GOOSE), v2] -> [C (GRAPE), v1]

        final VirtualMap map0 = createMap();
        final VirtualNodeCache cache0 = map0.getCache();
        // A: [C (APPLE), v0]
        // B: [C (BANANA), v0]
        // C: [C+U (CHERRY, CUTTLEFISH), v0]
        // D: [C+U (DATE, DOG), v0]
        // E: [C+U (EGGPLANT, EMU), v0]
        // F: [C (FIG), v0]
        map0.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        map0.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DOG, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EMU, TestValueCodec.INSTANCE);
        map0.put(F_KEY, FIG, TestValueCodec.INSTANCE);

        final VirtualMap map1 = map0.copy();
        final VirtualNodeCache cache1 = map1.getCache();

        // A: [U+D (AARDVARK), v1]
        // B: [D, v1]
        // C: [U (COMET), v1]
        // D: [U+D (DRACO), v1]
        // F: [U (FOX), v1]
        // G: [C (GRAPE), v1]
        map1.put(A_KEY, AARDVARK, TestValueCodec.INSTANCE);
        map1.remove(A_KEY);
        map1.remove(B_KEY);
        map1.put(C_KEY, COMET, TestValueCodec.INSTANCE);
        map1.put(D_KEY, DRACO, TestValueCodec.INSTANCE);
        map1.remove(D_KEY);
        map1.put(F_KEY, FOX, TestValueCodec.INSTANCE);
        map1.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap map2 = map1.copy();
        final VirtualNodeCache cache2 = map2.getCache();

        // A: [D, v2]
        // B: [C+U (BEAR, BLASTOFF), v2]
        // C: [D, v2]
        // D: [C+U (DISCIPLINE, DENMARK), v2]
        // E: [D, v2]
        // F: [D, v2]
        // G: [U (GOOSE), v2]
        map2.remove(A_KEY, TestValueCodec.INSTANCE);
        map2.put(B_KEY, BEAR, TestValueCodec.INSTANCE);
        map2.put(B_KEY, BLASTOFF, TestValueCodec.INSTANCE);
        map2.remove(C_KEY);
        map2.put(D_KEY, DISCIPLINE, TestValueCodec.INSTANCE);
        map2.put(D_KEY, DENMARK, TestValueCodec.INSTANCE);
        map2.remove(E_KEY);
        map2.remove(F_KEY);
        map2.put(G_KEY, GOOSE, TestValueCodec.INSTANCE);

        final VirtualMap map3 = map2.copy();
        final VirtualNodeCache cache3 = map3.getCache();

        // B: [D, v3]
        // C: [C+U+D (CHEMISTRY, CHAD), v3]
        // E: [C+U (EXOPLANET, ECOLOGY), v3]
        // F: [C (FORCE), v3]
        // G: [U (GRAVITY), v3]
        map3.remove(B_KEY);
        map3.put(C_KEY, CHEMISTRY, TestValueCodec.INSTANCE);
        map3.put(C_KEY, CHAD, TestValueCodec.INSTANCE);
        map3.remove(C_KEY);
        map3.put(E_KEY, EXOPLANET, TestValueCodec.INSTANCE);
        map3.put(E_KEY, ECOLOGY, TestValueCodec.INSTANCE);
        map3.put(F_KEY, FORCE, TestValueCodec.INSTANCE);
        map3.put(G_KEY, GRAVITY, TestValueCodec.INSTANCE);

        // One last copy, so we can get the dirty leaves without an exception
        final VirtualMap map4 = map3.copy();

        final List<VirtualLeafBytes> deletedLeaves0 = cache0.deletedLeaves().toList();
        assertEquals(0, deletedLeaves0.size(), "No deleted leaves in cache0");

        cache0.seal();
        cache1.seal();
        cache0.merge();
        validateDeletedLeaves(
                cache1.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, B_KEY, D_KEY), "cache1");

        cache2.seal();
        cache1.merge();
        validateDeletedLeaves(
                cache2.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, C_KEY, E_KEY, F_KEY), "cache2");

        cache3.seal();
        cache2.merge();
        validateDeletedLeaves(
                cache3.deletedLeaves().collect(Collectors.toList()), Set.of(A_KEY, B_KEY, C_KEY), "cache3");

        map0.release();
        map1.release();
        map2.release();
        map3.release();
        map4.release();
    }

    @Test
    void garbageAboveThresholdResultsInGC() throws InterruptedException {
        final int size = 63;
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("virtualMap.copyFlushCandidateThreshold", "64000")
                // Enable flushes, if garbage is less than 55%
                .withValue("virtualMap.percentFlushGarbageThreshold", "5")
                .build();
        final VirtualMap map0 = createMap(config);

        for (int i = 0; i < size; i++) {
            map0.put(TestKey.longToKey(i), new TestValue("" + i), TestValueCodec.INSTANCE);
        }
        final VirtualMap map1 = map0.copy();
        map0.getHash();
        map0.release();

        Thread.sleep(500);
        assertFalse(map0.isFlushed()); // Flush threshold not exceeded
        assertFalse(map0.isMerged()); // Next copy is not released yet, can't merge

        final VirtualMap map2 = map1.copy();
        map1.getHash();
        map1.release();

        // map1: no leaf updates, no dirty hashes -> estimated size is only concurrent array overhead,
        // 3 arrays x 8K each = 24K, so it should be merged
        assertEventuallyTrue(map0::isMerged, Duration.ofMillis(1000), "Copy 0 must be merged");
        assertFalse(map0.isFlushed());

        // map1 is released -> map0 is merged to it. Estimated map1 size: 24K array overhead from
        // map0 (3 arrays x 8K each), 2K leaf updates from map0, 3K hash updates from map0, no
        // dirty leaves/hashes in map1 -> total size is slightly below 55K. Flush threshold is set
        // to a higher value, so map1 should not be flushed, but merged to map2

        // Update all the values. It will create some garbage, both leaves and hashes
        for (int i = 0; i < size; i++) {
            map2.put(TestKey.longToKey(i), new TestValue("u" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap map3 = map2.copy();
        map2.getHash();
        map2.release();

        assertEventuallyTrue(map1::isMerged, Duration.ofMillis(1000), "Copy 1 should be merged");
        assertFalse(map1.isFlushed());

        // map2: all changes from map1, plus some dirty leaves, plus some dirty hashes, plus
        // concurrent arrays overhead - about 84K total. Garbage is 2K dirty leaves and 3K
        // dirty hashes - 5K garbage, which is slightly above 6%. Garbage threshold is set to 5%,
        // so map2 should be GCed+merged rather than flushed

        Thread.sleep(500);
        assertFalse(map2.isFlushed()); // Above garbage threshold -> no flush
        assertFalse(map2.isMerged()); // map3 is not released yet, can't merge

        final VirtualMap map4 = map3.copy();
        map3.getHash();
        map3.release();

        assertEventuallyTrue(map2::isMerged, Duration.ofMillis(1000), "Copy 2 should be merged");

        map4.release();
    }

    // This test case is quite similar to garbageAboveThresholdResultsInGC() above, except
    // the garbage threshold is set to 7% instead of 5%. It should trigger a GC + merge
    // instead of a flush. Size math for all copies is the same, see comments in the
    // previous test
    @Test
    void garbageBelowThresholdResultsInFlush() throws InterruptedException {
        final int size = 63;
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("virtualMap.copyFlushCandidateThreshold", "64000")
                // Enable flushes, if garbage is less than 50%
                .withValue("virtualMap.percentFlushGarbageThreshold", "7")
                .build();
        final VirtualMap map0 = createMap(config);

        for (int i = 0; i < size; i++) {
            map0.put(TestKey.longToKey(i), new TestValue("" + i), TestValueCodec.INSTANCE);
        }
        final VirtualMap map1 = map0.copy();
        map0.getHash();
        map0.release();

        Thread.sleep(500);
        assertFalse(map0.isFlushed()); // Flush threshold not exceeded
        assertFalse(map0.isMerged()); // Next copy is not released yet, can't merge

        final VirtualMap map2 = map1.copy();
        map1.getHash();
        map1.release();

        assertEventuallyTrue(map0::isMerged, Duration.ofMillis(1000), "Copy 0 must be merged");
        assertFalse(map0.isFlushed());

        for (int i = 0; i < size; i++) {
            map2.put(TestKey.longToKey(i), new TestValue("u" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap map3 = map2.copy();
        map2.getHash();
        map2.release();

        assertEventuallyTrue(map1::isMerged, Duration.ofMillis(1000), "Copy 1 should be merged");
        assertFalse(map1.isFlushed());

        assertEventuallyTrue(map2::isFlushed, Duration.ofMillis(1000), "Copy 2 should be flushed");
        assertFalse(map2.isMerged());

        map3.release();
    }

    @Test
    void garbageCollectRemovesDeletedLeaves() throws InterruptedException {
        final int size = 10;
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                // All copies are subject to flush or GC
                .withValue("virtualMap.copyFlushCandidateThreshold", "1")
                // Only zero garbage should result in a flush
                .withValue("virtualMap.percentFlushGarbageThreshold", "0.01")
                .build();
        final VirtualMap map0 = createMap(config);

        for (int i = 0; i < size; i++) {
            map0.put(TestKey.longToKey(i), new TestValue("" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap map1 = map0.copy();
        map0.getHash();
        // Zero garbage -> flush
        assertTrue(map0.shouldBeFlushed());
        map0.release();
        map0.waitUntilFlushed();

        final Bytes toDelete = TestKey.longToKey(size - 1);
        map1.remove(toDelete);

        final VirtualMap map2 = map1.copy();
        map1.getHash();
        // The deleted key results in a garbage mutation -> no flush, but GC
        assertFalse(map1.shouldBeFlushed());
        map1.release();

        // Create one more copy, so map2 becomes immutable/destroyed, and map1 can be merged
        final VirtualMap map3 = map2.copy();
        map2.getHash();
        map2.release();

        assertEventuallyTrue(map1::isMerged, Duration.ofMillis(1000), "Copy 1 should be merged");

        // map1 is merged, and in-memory mode is on. The deleted key should be removed both from
        // the cache and the data source
        assertFalse(map2.containsKey(toDelete));
        assertNull(map2.getBytes(toDelete));
        assertNull(map2.getCache().lookupLeafByKey(toDelete));
        assertFalse(map3.containsKey(toDelete));
        assertNull(map3.getBytes(toDelete));
        assertNull(map3.getCache().lookupLeafByKey(toDelete));

        map3.release();
    }

    private void validateDeletedLeaves(
            final List<VirtualLeafBytes> deletedLeaves, final Set<Bytes> expectedKeys, final String name) {

        assertEquals(expectedKeys.size(), deletedLeaves.size(), "Not enough deleted leaves in " + name);

        final Set<Bytes> keys =
                deletedLeaves.stream().map(VirtualLeafBytes::keyBytes).collect(Collectors.toSet());
        assertEquals(deletedLeaves.size(), keys.size(), "Two records with the same key exist in " + name);

        for (final var rec : deletedLeaves) {
            assertTrue(keys.remove(rec.keyBytes()), "A record does not have the expected key in " + name);
        }
    }
}
