// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectory;
import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.BREADTH_FIRST;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VM_LABEL;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.InMemoryDataSource;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestObjectKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.base.crypto.Hash;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("ALL")
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
    @DisplayName("A fresh map has both children")
    void freshMapHasBothChildren() {
        final VirtualMap vm = createMap();
        assertEquals(2, vm.getNumberOfChildren(), "VirtualMap size is wrong");
        assertNull(vm.getChild(0), "Unexpected null at index 0");
        assertNull(vm.getChild(1), "Unexpected non-null at index 1");
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
    void originalIsUnaffected() {
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

    // FUTURE WORK test deleting the same key two times in a row.
    // FUTURE WORK Test that a deleted node's value cannot be subsequently read.

    //    @Test
    //    @Tag(TestComponentTags.FCMAP)
    //    @DisplayName("Remove all leaves by always removing the first leaf")
    //    void removeFirstLeaf() {
    //        var fcm = createMap();
    //        fcm.put(A_KEY, APPLE);
    //        fcm.put(B_KEY, BANANA);
    //        fcm.put(C_KEY, CHERRY);
    //        fcm.put(D_KEY, DATE);
    //        fcm.put(E_KEY, EGGPLANT);
    //        fcm.put(F_KEY, FIG);
    //        fcm.put(G_KEY, GRAPE);
    //
    //        var original = fcm;
    //        fcm = fcm.copy();
    //        CRYPTO.digestTreeSync(original);
    //        original.release();
    //
    //        assertEquals(DATE, fcm.remove(D_KEY));
    //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, F_KEY, B_KEY, G_KEY);
    //        assertEquals(BANANA, fcm.remove(B_KEY));
    //        assertLeafOrder(fcm, A_KEY, E_KEY, C_KEY, F_KEY, G_KEY);
    //        assertEquals(CHERRY, fcm.remove(C_KEY));
    //        assertLeafOrder(fcm, A_KEY, E_KEY, F_KEY, G_KEY);
    //        assertEquals(APPLE, fcm.remove(A_KEY));
    //        assertLeafOrder(fcm, G_KEY, E_KEY, F_KEY);
    //        assertEquals(FIG, fcm.remove(F_KEY));
    //        assertLeafOrder(fcm, G_KEY, E_KEY);
    //        assertEquals(GRAPE, fcm.remove(G_KEY));
    //        assertLeafOrder(fcm, E_KEY);
    //        assertEquals(EGGPLANT, fcm.remove(E_KEY));
    //
    //        // FUTURE WORK validate hashing works as expected
    //
    //    }

    //    @Test
    //    @Tag(TestComponentTags.FCMAP)
    //    @DisplayName("Remove a middle leaf")
    //    void removeMiddleLeaf() {
    //        var fcm = createMap();
    //        fcm.put(A_KEY, APPLE);
    //        fcm.put(B_KEY, BANANA);
    //        fcm.put(C_KEY, CHERRY);
    //        fcm.put(D_KEY, DATE);
    //        fcm.put(E_KEY, EGGPLANT);
    //        fcm.put(F_KEY, FIG);
    //        fcm.put(G_KEY, GRAPE);
    //
    //        var original = fcm;
    //        fcm = fcm.copy();
    //        CRYPTO.digestTreeSync(original);
    //        original.release();
    //
    //        assertEquals(FIG, fcm.remove(F_KEY));
    //        assertEquals(DATE, fcm.remove(D_KEY));
    //        assertEquals(APPLE, fcm.remove(A_KEY));
    //        assertEquals(BANANA, fcm.remove(B_KEY));
    //        assertEquals(EGGPLANT, fcm.remove(E_KEY));
    //        assertEquals(CHERRY, fcm.remove(C_KEY));
    //        assertEquals(GRAPE, fcm.remove(G_KEY));
    //
    //        // FUTURE WORK validate hashing works as expected
    //
    //    }

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
        assertTrue(copy4.getPipeline().awaitTermination(5, SECONDS), "Timed out");
        assertTrue(ds.isClosed(), "Should now be released");
    }

    @Test
    @Tags({@Tag("VirtualMap"), @Tag("Pipeline"), @Tag("VMAP-021")})
    @DisplayName("Database is closed if prematurely terminated")
    void databaseClosedWhenExpresslyTerminated() throws InterruptedException {
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
        copy2.getPipeline().terminate();
        assertTrue(copy2.getPipeline().awaitTermination(5, SECONDS), "Timed out");
        assertTrue(ds.isClosed(), "Should now be released");
    }

    /*
     * Test iteration and hashing
     **/

    //    @Test
    //    @Tag(TestComponentTags.FCMAP)
    //    @DisplayName("Newly created maps have null hashes for everything")
    //    void nullHashesOnNewMap() throws ExecutionException, InterruptedException {
    //        var fcm = createMap();
    //        fcm.put(A_KEY, APPLE);
    //        fcm.put(B_KEY, BANANA);
    //        fcm.put(C_KEY, CHERRY);
    //        fcm.put(D_KEY, DATE);
    //        fcm.put(E_KEY, EGGPLANT);
    //        fcm.put(F_KEY, FIG);
    //        fcm.put(G_KEY, GRAPE);
    //
    // FUTURE WORK Cannot iterate until after hashing, which invalidates the test
    //        var completed = fcm;
    //        fcm = fcm.copy();
    //        completed.hash().get();
    //        final var breadthItr = new MerkleBreadthFirstIterator<MerkleNode, MerkleNode>(completed);
    //        while (breadthItr.hasNext()) {
    //            assertNull(breadthItr.next().getHash());
    //        }
    //    }

    @Test
    @DisplayName("Hashed maps have non-null hashes on everything")
    void nonNullHashesOnHashedMap() throws ExecutionException, InterruptedException {
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
        TestMerkleCryptoFactory.getInstance().digestTreeSync(completed);

        final Iterator<MerkleNode> breadthItr = completed.treeIterator().setOrder(BREADTH_FIRST);
        while (breadthItr.hasNext()) {
            assertNotNull(breadthItr.next().getHash(), "Expected a value");
        }

        completed.release();
        fcm.release();
    }

    @Test
    @DisplayName("Million sized hashed maps have non-null hashes on everything")
    void millionNonNullHashesOnHashedMap() throws ExecutionException, InterruptedException {
        VirtualMap fcm = createMap();
        for (int i = 0; i < 1_000_000; i++) {
            fcm.put(TestKey.longToKey(i), new TestValue("" + i), TestValueCodec.INSTANCE);
        }

        final VirtualMap completed = fcm;
        fcm = fcm.copy();

        try {
            final Hash firstHash = TestMerkleCryptoFactory.getInstance().digestTreeSync(completed);
            final Iterator<MerkleNode> breadthItr = completed.treeIterator().setOrder(BREADTH_FIRST);
            while (breadthItr.hasNext()) {
                assertNotNull(breadthItr.next().getHash(), "Expected a value");
            }

            final Random rand = new Random(1234);
            for (int i = 0; i < 10_000; i++) {
                final int index = rand.nextInt(1_000_000);
                final int value = 1_000_000 + rand.nextInt(1_000_000);
                fcm.put(TestKey.longToKey(index), new TestValue("" + value), TestValueCodec.INSTANCE);
            }

            final VirtualMap second = fcm;
            fcm = copyAndRelease(fcm);
            final Hash secondHash = TestMerkleCryptoFactory.getInstance().digestTreeSync(second);
            assertNotSame(firstHash, secondHash, "Wrong value");
        } finally {
            fcm.release();
            completed.release();
            assertTrue(fcm.getPipeline().awaitTermination(10, SECONDS), "Pipeline termination timed out");
        }
    }

    @Test
    @DisplayName("put should not mutate old copies")
    void checkPutMutation() throws InterruptedException {
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
    void nullHashesOnDirtyNodes() throws ExecutionException, InterruptedException {
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
    void testAsyncHashing() throws ExecutionException, InterruptedException {
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
     * This test validates that for the basic tree below, the routes are set correctly.
     * When the tests are moved to the swirlds-test module, we should use a MerkleMap
     * and insert one million elements, and insert the same elements into a
     * {@link VirtualMap}. Then, we iterate over the routes of both maps and their
     * routes should match.
     *
     * <pre>
     *                      VirtualMap
     *                         []
     *                      /     \
     *                     /       \
     *                 Internal     B
     *                 [1, 0]     [1, 1]
     *                 /   \
     *                /     \
     *               A       C
     *        [1, 0, 0]    [1, 0, 1]
     * </pre>
     */
    @Test
    void routesSetForBasicTree() {
        final VirtualMap vm = createMap();
        vm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        vm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        vm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);

        final List<MerkleNode> nodes = new ArrayList<>();
        vm.forEachNode(node -> {
            nodes.add(node);
        });

        assertEquals(MerkleRouteFactory.buildRoute(0, 0), nodes.get(0).getRoute(), "VirtualLeafNode A");
        assertEquals(MerkleRouteFactory.buildRoute(0, 1), nodes.get(1).getRoute(), "VirtualLeafNode C");
        assertEquals(MerkleRouteFactory.buildRoute(0), nodes.get(2).getRoute(), "VirtualInternalNode");
        assertEquals(MerkleRouteFactory.buildRoute(1), nodes.get(3).getRoute(), "VirtualLeafNode B");
        assertEquals(MerkleRouteFactory.buildRoute(), nodes.get(4).getRoute(), "VirtualMap");
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
     * Bug #4233 was caused by an NPE when flushing a copy that had been detached for the
     * sake of state saving. This happened because the detach for state saving does not
     * result in the detached state having a data source.
     */
    @Test
    void canFlushDetachedStateForStateSaving() throws IOException, InterruptedException {
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

        // Detach, and then make another copy which should cause it to flush.
        map1.enableFlush();
        map1.detach();
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
    void testNodeCacheSizeMetric() throws IOException, InterruptedException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
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

        Metric metric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_lifecycle_nodeCacheSizeB_Test");
        assertNotNull(metric);
        if (!(metric instanceof LongGauge)) {
            throw new AssertionError("nodeCacheSizeMb metric is not a gauge");
        }

        long metricValue = (long) metric.get(ValueType.VALUE);
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

            long newValue = (long) metric.get(ValueType.VALUE);
            assertTrue(
                    newValue >= metricValue,
                    "Node cache size must be increasing" + " old value = " + metricValue + " new value = " + newValue);
            metricValue = newValue;
        }

        final long value = metricValue;

        final VirtualMap lastMap = map0;
        lastMap.enableFlush();
        VirtualMap map1 = map0.copy();
        map0.release();
        lastMap.waitUntilFlushed();
        map1.release();

        assertEventuallyTrue(
                () -> {
                    long lastValue = (long) metric.get(ValueType.VALUE);
                    return lastValue < value;
                },
                Duration.ofSeconds(4),
                "Node cache size must decrease after flush");
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Tests vMapFlushes metric")
    void testFlushCount() throws IOException, InterruptedException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
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

        // createMap() creates a map labelled "Test"
        Metric metric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_lifecycle_flushCount_Test");
        assertNotNull(metric);
        if (!(metric instanceof Counter counterMetric)) {
            throw new AssertionError("flushCount metric is not a counter");
        }
        // There is a potential race condition here, as we release `VirtualRootNode.flushLatch`
        // before we update the statistics (see https://github.com/hashgraph/hedera-services/issues/8439)
        assertEventuallyEquals(
                flushCount,
                () -> counterMetric.get(),
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
        String label = "serializationTest";
        final VirtualMap map0 = createMap(label);
        assertEquals(label, map0.getLabel());
        map0.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        map0.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        map0.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);
        map0.put(D_KEY, DATE, TestValueCodec.INSTANCE);
        map0.put(E_KEY, EGGPLANT, TestValueCodec.INSTANCE);
        map0.put(F_KEY, FIG, TestValueCodec.INSTANCE);
        map0.put(G_KEY, GRAPE, TestValueCodec.INSTANCE);

        final VirtualMap map1 = map0.copy(); // this should make map0 immutable
        assertEquals(label, map1.getLabel());
        assertNotNull(map0.getHash(), "Hash should have been produced for map0");
        assertTrue(map0.isImmutable(), "Copied VirtualMap should have been immutable");
        assertVirtualMapsEqual(map0, map1);

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);
        // serialize the existing maps
        map0.serialize(out, testDirectory);

        final SerializableDataInputStream in =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final VirtualMap map2 = createMap();
        map2.deserialize(in, testDirectory, map0.getVersion());
        assertEquals(label, map2.getLabel());
        assertVirtualMapsEqual(map0, map2);

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
        VirtualMap map = createMap("insertRemoveAndModifyOneMillion");
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
            assertTrue(map.getPipeline().awaitTermination(60, SECONDS), "Pipeline termination timed out");
        }
    }

    @Test
    @Tags({@Tag("VirtualMerkle")})
    @DisplayName("Delete a value that was moved to a different virtual path")
    void deletedObjectLeavesOnFlush() throws InterruptedException {
        VirtualMap map = createMap("deletedObjectLeavesOnFlush");
        for (int i = 0; i < 8; i++) {
            map.put(TestObjectKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
        }

        map.enableFlush();

        RecordAccessor records = map.getRecords();
        // Check that key/value 0 is at path 7
        VirtualLeafBytes leaf = records.findLeafRecord(8);
        assertNotNull(leaf);
        assertEquals(TestObjectKey.longToKey(4), leaf.keyBytes());
        assertEquals(new TestValue(4).toBytes(), leaf.valueBytes());
        assertEquals(new TestValue(4), leaf.value(TestValueCodec.INSTANCE));

        VirtualMap copy = map.copy();
        map.release();
        map.waitUntilFlushed();
        map = copy;

        // Move key/value to a different path, then delete
        map.remove(TestObjectKey.longToKey(0));
        map.remove(TestObjectKey.longToKey(2));
        map.put(TestObjectKey.longToKey(8), new TestValue(8), TestValueCodec.INSTANCE);
        map.put(TestObjectKey.longToKey(0), new TestValue(0), TestValueCodec.INSTANCE);
        map.remove(TestObjectKey.longToKey(0));

        map.enableFlush();

        copy = map.copy();
        map.release();
        map.waitUntilFlushed();
        map = copy;

        // During this second flush, key/value 0 must be deleted from the map despite it's
        // path the virtual tree doesn't match the path in the data source
        assertFalse(map.containsKey(TestObjectKey.longToKey(0)));
        assertNull(map.get(TestObjectKey.longToKey(0), TestValueCodec.INSTANCE));
        assertNull(map.getBytes(TestObjectKey.longToKey(0)));

        map.release();
    }

    @Test
    void testEnableVirtualRootFlush() throws ExecutionException, InterruptedException {
        VirtualMap fcm0 = createMap();
        fcm0.postInit();
        assertFalse(fcm0.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm1 = fcm0.copy();
        fcm1.postInit();
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm2 = fcm1.copy();
        fcm2.postInit();
        assertFalse(fcm1.shouldBeFlushed(), "map should not yet be flushed");

        VirtualMap fcm3 = fcm2.copy();
        fcm3.postInit();
        fcm3.enableFlush();
        assertTrue(fcm3.shouldBeFlushed(), "map should now be flushed");

        fcm0.release();
        fcm1.release();
        fcm2.release();
        fcm3.release();
    }

    @Test
    @DisplayName("If there are no dirty leaves, previous copy's root hash is used")
    void emptyDirtyLeavesResultInHashFromPreviousCopy() throws ExecutionException, InterruptedException {
        final InMemoryDataSource ds = new InMemoryDataSource("emptyDirtyLeavesResultInHashFromPreviousCopy");
        final VirtualDataSourceBuilder builder = new InMemoryBuilder();

        final VirtualMap vm = new VirtualMap(VM_LABEL, builder, CONFIGURATION);
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
    void removeOnlyElement() throws ExecutionException, InterruptedException {

        final VirtualMap fcm = createMap();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);

        final VirtualMap copy = fcm.copy();
        copy.postInit();
        fcm.release();
        fcm.waitUntilFlushed();

        final TestValue removed = copy.remove(A_KEY, TestValueCodec.INSTANCE);
        assertEquals(APPLE, removed, "Wrong value");

        // FUTURE WORK validate hashing works as expected

        copy.release();
    }

    @Test
    @DisplayName("Remove element twice")
    void removeElementTwice() throws ExecutionException, InterruptedException {
        final VirtualMap fcm = createMap();
        fcm.enableFlush();
        fcm.put(A_KEY, APPLE, TestValueCodec.INSTANCE);
        fcm.put(B_KEY, BANANA, TestValueCodec.INSTANCE);
        fcm.put(C_KEY, CHERRY, TestValueCodec.INSTANCE);

        final VirtualMap copy = fcm.copy();
        copy.postInit();
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
    void removeInReverseOrder() throws ExecutionException, InterruptedException {
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
        copy.postInit();
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
     * This test deserializes a VirtualRootNode that was serialized with version 2 of the serialization format.
     * This node contains 100 entries, but only 88 of them are valid. The other 12 are deleted.
     */
    @Test
    void testSerializeDeserialize() throws IOException {
        String fileName = "rootNode.bin";
        serializeRoot(fileName);
        deserializeRootNodeAndVerify(
                new FileInputStream(testDirectory.resolve(fileName).toFile()),
                VirtualMap.ClassVersion.NO_VIRTUAL_ROOT_NODE);
    }

    private void deserializeRootNodeAndVerify(InputStream resourceAsStream, int version) throws IOException {
        final VirtualMap root = createMap();

        try (SerializableDataInputStream input = new SerializableDataInputStream(resourceAsStream)) {
            root.deserialize(input, testDirectory, version);
            root.postInit();
            final VirtualNodeCache cache = root.getCache();
            for (int i = 0; i < 100; i++) {
                final Bytes key = TestKey.longToKey(i);
                if (version >= VirtualRootNode.ClassVersion.VERSION_3_NO_NODE_CACHE) {
                    // Cache must be empty, all values must be in the data source
                    assertNull(cache.lookupLeafByKey(key));
                }
                if (i % 7 != 0) {
                    assertEquals(new TestValue(i).toBytes(), root.getBytes(key));
                    assertEquals(new TestValue(i), root.get(key, TestValueCodec.INSTANCE));
                } else {
                    assertNull(root.get(TestKey.longToKey(i), null));
                }
            }
            root.release();
        }
    }

    private void serializeRoot(String fileName) throws IOException {
        try (FileOutputStream fileOutputStream =
                        new FileOutputStream(testDirectory.resolve(fileName).toFile());
                SerializableDataOutputStream out = new SerializableDataOutputStream(fileOutputStream)) {
            VirtualMap testKeyTestValueVirtualRootNode = prepareRootForSerialization();
            testKeyTestValueVirtualRootNode.serialize(out, testDirectory);
            fileOutputStream.flush();
            testKeyTestValueVirtualRootNode.release();
        }
    }

    private static VirtualMap prepareRootForSerialization() {
        final VirtualMap root = createMap();
        root.enableFlush();

        Set<Bytes> keysToRemove = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            root.put(TestKey.longToKey(i), new TestValue(i), TestValueCodec.INSTANCE);
            if (i % 7 == 0) {
                keysToRemove.add(TestKey.longToKey(i));
            }
        }

        for (Bytes key : keysToRemove) {
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
        assertEquals(totalSize, root2.size(), "New map is expected to have all data and VirtualMapMetadata");
        for (int index = 0; index < totalSize; index++) {
            final Bytes key = TestKey.longToKey(index);
            final TestValue expectedValue = new TestValue(index);
            final TestValue value = root2.get(key, TestValueCodec.INSTANCE);
            assertEquals(expectedValue, value, "Values have the same content");
        }
    }

    @Test
    @DisplayName("Snapshot Test")
    void snapshotTest() throws IOException {
        final List<Path> paths = new LinkedList<>();
        paths.add(Path.of("asdf"));
        for (final Path destination : paths) {
            final VirtualMap original = new VirtualMap("test", new InMemoryBuilder(), CONFIGURATION);
            final VirtualMap copy = original.copy();

            original.getHash(); // forces copy to become hashed
            original.getPipeline().pausePipelineAndRun("snapshot", () -> {
                original.snapshot(destination);
                return null;
            });
            assertTrue(original.isDetached(), "root should be detached");

            original.release();
            copy.release();
        }
    }

    @Test
    @DisplayName("Snapshot and restore")
    void snapshotAndRestore() throws IOException {
        final VirtualDataSourceBuilder dsBuilder = new InMemoryBuilder();
        final List<VirtualMap> copies = new LinkedList<>();
        final VirtualMap copy0 = new VirtualMap("test", dsBuilder, CONFIGURATION);
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
        final Path snapshotPath =
                LegacyTemporaryFileBuilder.buildTemporaryDirectory("snapshotAndRestore", CONFIGURATION);
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream();
                final SerializableDataOutputStream out = new SerializableDataOutputStream(bout)) {
            copy5.serialize(out, snapshotPath);
            try (final ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
                    final SerializableDataInputStream in = new SerializableDataInputStream(bin)) {
                final VirtualMap restored = new VirtualMap(CONFIGURATION);
                restored.deserialize(in, snapshotPath, copy0.getVersion());
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
            }
        } finally {
            copies.forEach(VirtualMap::release);
        }
    }

    @Test
    @DisplayName("Detach Test")
    void detachTest() throws IOException {
        final VirtualMap original = new VirtualMap("test", new InMemoryBuilder(), CONFIGURATION);
        Bytes testKey = Bytes.wrap("testKey");
        original.put(testKey, new TestValue("testValue"), TestValueCodec.INSTANCE);
        final VirtualMap copy = original.copy();

        original.getHash(); // forces copy to become hashed

        final RecordAccessor detachedCopy = original.getPipeline().pausePipelineAndRun("copy", original::detach);
        assertTrue(original.isDetached(), "root should be detached");
        assertNotNull(detachedCopy);

        VirtualMapMetadata originalMetadata = original.getMetadata();
        // let's change the original state and make sure that the detached copy is not affected
        originalMetadata.setFirstLeafPath(-1);
        originalMetadata.setLastLeafPath(-1);
        VirtualLeafBytes leafRecord = detachedCopy.findLeafRecord(1L);
        assertNotNull(leafRecord);
        assertEquals(testKey, leafRecord.keyBytes(), "Path does not match");

        original.release();
        copy.release();
        detachedCopy.close();
    }

    @Test
    @DisplayName("Default flush threshold not zero")
    void defaultFlushThresholdTest() {
        final VirtualMapConfig config =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);
        VirtualMap root = createMap();
        assertEquals(config.copyFlushCandidateThreshold(), root.getFlushCandidateThreshold());
        root.release();
    }

    @Test
    @DisplayName("Flush interval is inherited by copies")
    void flushIntervalInheritedTest() {
        final long threshold = 12345678L;
        final VirtualMapConfig config =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(VirtualMapConfig.class);

        final int flushInterval = config.flushInterval();
        VirtualMap root = createMap();
        root.setFlushCandidateThreshold(threshold);
        for (int i = 0; i <= flushInterval; i++) {
            assertEquals(threshold, root.getFlushCandidateThreshold());
            VirtualMap copy = root.copy();
            copy.postInit();
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
        VirtualMap map = createMap();
        map.setFlushCandidateThreshold(0);
        assertFalse(map.shouldBeFlushed()); // the very first copy is never flushed
        for (int i = 0; i < flushInterval; i++) {
            VirtualMap copy = map.copy();
            copy.postInit();
            map.release();
            map = copy;
        }
        assertTrue(map.shouldBeFlushed());
        map.release();
    }

    @Test
    @DisplayName("Default zero flush threshold")
    void defaultZeroFlushThresholdTest() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, "0")
                .getOrCreateConfig();

        final VirtualDataSourceBuilder builder = new InMemoryBuilder();
        VirtualMap map = new VirtualMap(VM_LABEL, builder, configuration);

        assertEquals(0, map.getFlushCandidateThreshold());
        final int flushInterval =
                configuration.getConfigData(VirtualMapConfig.class).flushInterval();
        for (int i = 0; i < flushInterval; i++) {
            VirtualMap copy = map.copy();
            copy.postInit();
            map.release();
            map = copy;
        }
        final VirtualMap copyShouldBeFlushed = map;
        map.setFlushCandidateThreshold(12345678L);
        for (int i = 0; i < flushInterval; i++) {
            VirtualMap copy = map.copy();
            copy.postInit();
            map.release();
            map = copy;
        }
        final VirtualMap copyShouldNotBeFlushed = map;
        // shouldBeFlushed() can only be called on released copies, so create one more copy to
        // release copyShouldNotBeFlushed
        final VirtualMap finalCopy = map.copy();
        map.release();

        assertTrue(copyShouldBeFlushed.shouldBeFlushed());
        assertFalse(copyShouldNotBeFlushed.shouldBeFlushed()); // should still have a custom flush threshold

        finalCopy.release();
    }

    @Test
    @DisplayName("Copy of a root node with terminated pipeline")
    void copyOfRootNodeWithTerminatedPipeline() {
        VirtualMap map = createMap();
        map.getPipeline().terminate();
        assertThrows(IllegalStateException.class, map::copy);
    }

    @Test
    void getVersion() {
        assertEquals(4, createMap().getVersion());
    }

    @Test
    void postInitNoOpIfLearnerTreeViewIsSet() {
        VirtualMap root = createMap();
        VirtualMap anotherRoot = createMap();
        anotherRoot.computeHash();
        root.setupWithOriginalNode(anotherRoot);
        assertDoesNotThrow(() -> root.postInit());
    }

    // based heavily on VirtualMapGroup::validateCopy(), but modified to just compare two VirtualMaps, instead of
    // also taking in a "ref" Math of values to compare each map to.
    private void assertVirtualMapsEqual(final VirtualMap mapA, final VirtualMap mapB) {
        final boolean immutable = mapA.isImmutable();

        if (mapA.size() != mapB.size()) {
            throw new RuntimeException("size does not match"); // Add a breakpoint here
        }

        final Map<MerkleRoute, Hash> hashes = new HashMap<>();

        mapA.forEachNode((final MerkleNode node) -> {
            if (immutable) {
                hashes.put(node.getRoute(), node.getHash());
            }

            if (node instanceof VirtualLeafNode) {
                final VirtualLeafNode leaf = node.cast();

                final Bytes key = leaf.getKey();

                final TestValue value = leaf.getValue(TestValueCodec.INSTANCE);
                if (!Objects.equals(mapB.get(key, TestValueCodec.INSTANCE), value)) {
                    throw new RuntimeException("values do not match for key " + key + ": mapA = " + value + ", mapB ="
                            + mapB.get(key, TestValueCodec.INSTANCE) + "."); // Add a breakpoint here
                }

                final Bytes valueBytes = leaf.getValue();
                if (!Objects.equals(mapB.getBytes(key), valueBytes)) {
                    throw new RuntimeException("value bytes do not match for key " + key + ": mapA = " + value
                            + ", mapB =" + mapB.getBytes(key) + "."); // Add a breakpoint here
                }
            }
        });

        mapB.forEachNode((final MerkleNode node) -> {
            if (immutable) {
                if (!hashes.containsKey(node.getRoute())) {
                    throw new RuntimeException("topology differs between trees"); // Add a breakpoint here
                }
                if (!Objects.equals(hashes.get(node.getRoute()), node.getHash())) {
                    throw new RuntimeException("hashes differ between trees"); // Add a breakpoint here
                }
            }

            if (node instanceof VirtualLeafNode) {
                final VirtualLeafNode leaf = node.cast();

                final Bytes key = leaf.getKey();

                final TestValue value = leaf.getValue(TestValueCodec.INSTANCE);
                if (!Objects.equals(mapA.get(key, TestValueCodec.INSTANCE), value)) {
                    throw new RuntimeException("values do not match for key " + key + ": mapB = " + value + ", mapA ="
                            + mapA.get(key, TestValueCodec.INSTANCE) + "."); // Add a breakpoint here
                }
            }
        });
    }

    private boolean containsRegex(final String regex, final String haystack) {
        final Pattern exp = Pattern.compile(regex, Pattern.MULTILINE);
        final Matcher m = exp.matcher(haystack);

        return m.find();
    }

    private int countRegex(final String regex, final String haystack) {
        final Pattern exp = Pattern.compile(regex, Pattern.MULTILINE);
        final Matcher m = exp.matcher(haystack);

        int hits = 0;
        while (!m.hitEnd()) {
            if (m.find()) {
                hits++;
            }
        }

        return hits;
    }
}
