// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import static java.util.Map.Entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.KeyedMerkleLong;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.dummy.AccessibleMerkleMap;
import com.swirlds.merkle.test.fixtures.map.dummy.FCQValue;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import com.swirlds.merkle.test.fixtures.map.util.KeyValueProvider;
import com.swirlds.merkle.test.fixtures.map.util.MerkleMapTestUtil;
import com.swirlds.merkle.tree.MerkleBinaryTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MerkleMap Tests")
class MerkleMapTests {

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);
    private static MerkleCryptography cryptography;
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        MerkleMapTestUtil.loadLogging();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
        cryptography = TestMerkleCryptoFactory.getInstance();
    }

    @AfterAll
    static void shutDown() {
        EXECUTOR_SERVICE.shutdown();
    }

    protected Stream<Arguments> buildArguments() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final KeyValueProvider keyValueProvider : KeyValueProvider.values()) {
            arguments.add(Arguments.of(0, keyValueProvider));
            arguments.add(Arguments.of(1, keyValueProvider));
            arguments.add(Arguments.of(2, keyValueProvider));
            arguments.add(Arguments.of(3, keyValueProvider));
            arguments.add(Arguments.of(100, keyValueProvider));
            arguments.add(Arguments.of(10_000, keyValueProvider));
        }

        return arguments.stream();
    }

    protected Stream<Arguments> buildNumberOfModifications() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(1));
        arguments.add(Arguments.of(100));
        arguments.add(Arguments.of(1_000));
        arguments.add(Arguments.of(10_000));
        return arguments.stream();
    }

    @ParameterizedTest
    @EnumSource(KeyValueProvider.class)
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Replaces Invalid Key")
    <V extends MerkleNode & Keyed<Key>> void replacesInvalidKey(final KeyValueProvider provider) {
        final Key key = provider.getDefaultKey();
        final V value = provider.getDefaultValue();
        final MerkleMap<Key, V> mm = new MerkleMap<>();

        assertThrows(IllegalStateException.class, () -> mm.replace(key, value), "expected this method to fail");

        assertEquals(0, mm.size(), "expected map to be empty");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Put Multiple Values")
    <V extends MerkleNode & Keyed<Key>> void putMultipleValues(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        assertEquals(size, mm.size(), "expected map to be the specified size");
        assertEquals(size, mm.values().size(), "value set expected to match size");
        assertEquals(size, mm.entrySet().size(), "entry set expected to match size");
        assertEquals(size, mm.keySet().size(), "key set expected to match size");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Put Multiple Values Twice")
    <V extends MerkleNode & Keyed<Key>> void putsMultipleValuesTwice(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final int newLimit = 2 * size;
        provider.insertIntoMap(size, newLimit, mm);
        assertEquals(newLimit, mm.size(), "expected to find map at specified size");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Inserts One Million Elements")
    <V extends MerkleNode & Keyed<Key>> void insertsOneMillionElements(
            final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        assertEquals(size, mm.size(), "expected map to be specified size");
        mm.release();
        assertEquals(size, mm.size(), "expected map to be the same size after release");
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Puts Elements From Another MM")
    <V extends MerkleNode & Keyed<Key>> void putsElementsFromAnotherMM(
            final int size, final KeyValueProvider provider) {

        final MerkleMap<Key, V> mm = new MerkleMap<>();
        final Map<Key, V> map = new HashMap<>();
        provider.insertIntoMap(0, size, map);

        mm.putAll(map);

        assertEquals(size, mm.getSize(), "expected map to be specified size");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Deletes Values")
    <V extends MerkleNode & Keyed<Key>> void deletesValues(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        assertEquals(size, mm.size(), "expected map to be specified size");

        for (int index = 0; index < size; index++) {
            final Key key = provider.getKeyBasedOnIndex(index);
            mm.remove(key);
        }

        assertEquals(0, mm.size(), "expected map to be empty");
        mm.release();
    }

    @ParameterizedTest
    @EnumSource(KeyValueProvider.class)
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Replace Invalid Key")
    void replaceInvalidKey(final KeyValueProvider provider) {
        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        final Value value = Value.newBuilder().build();
        final Key key = provider.getKeyBasedOnIndex(0);
        assertThrows(IllegalStateException.class, () -> mm.replace(key, value), "expected this method to fail");

        mm.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Get Null Key")
    <V extends MerkleNode & Keyed<Key>> void getNullKey() {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        final Exception exception = assertThrows(
                NullPointerException.class,
                () -> mm.get(null),
                "expected method to throw null pointer exception for null key");
        assertEquals("Null keys are not allowed", exception.getMessage(), "expected error message not found");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Clears Values")
    <V extends MerkleNode & Keyed<Key>> void clearsValues(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        assertEquals(size, mm.size(), "expected map to be specified size");

        mm.clear();

        assertEquals(0, mm.size(), "expected map to be empty");
        mm.release();
    }

    @ParameterizedTest
    @EnumSource(KeyValueProvider.class)
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Replace First Element")
    <V extends MerkleNode & Keyed<Key>> void replaceFirstElement(final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        final V value = provider.getDefaultValue();
        final Key key = provider.getDefaultKey();

        mm.put(key, value);

        final V newValue = provider.buildRandomValue();
        mm.put(key, newValue);

        final V returnedNewValue = mm.get(key);

        assertEquals(newValue, returnedNewValue, "expected value in map to equal value put into map");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Puts and Deletes One Element Multiple Times")
    <V extends MerkleNode & Keyed<Key>> void putsAndDeletesOneElementMultipleTimes(
            final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();

        for (int index = 0; index < size; index++) {
            final Key key = provider.getKeyBasedOnIndex(index);
            final V value = provider.buildRandomValue();
            mm.put(key, value);
            mm.remove(key);
        }
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Key Set Remains Invariant After Changes")
    <V extends MerkleNode & Keyed<Key>> void keySetRemainsInvariantAfterChanges(
            final int size, final KeyValueProvider provider) {

        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);
        final Set<Key> keySet = mm.keySet();

        assertEquals(size, keySet.size(), "expected the key set to be the same size as the map");

        mm.clear();

        assertEquals(0, mm.size(), "expected map to be empty");
        assertEquals(size, keySet.size(), "expected key set to be the same size as the map");
        assertEquals(0, mm.keySet().size(), "expected key set to be empty");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Values Remains Invariant After Changes")
    <V extends MerkleNode & Keyed<Key>> void valuesRemainsInvariantAfterChanges(
            final int size, final KeyValueProvider provider) {

        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);
        final Collection<V> values = mm.values();

        assertEquals(size, values.size(), "expected number of values to match size of map");

        mm.clear();

        assertEquals(0, mm.size(), "expected map to be empty");
        assertEquals(size, values.size(), "expected number of values to match size of map");
        assertEquals(0, mm.values().size(), "expected there to be no values in the map");
        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Original mm Is Immutable For Put")
    <V extends MerkleNode & Keyed<Key>> void originalIsImmutableForPut(int size, final KeyValueProvider provider) {
        size++;
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> copy = mm.copy();

        final int limit = size;
        final Exception exception = assertThrows(
                MutabilityException.class,
                () -> provider.insertIntoMap(limit, 2 * limit, mm),
                "expected this method to fail");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "did not find expected error message");
        mm.release();
        copy.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Original MerkleMap Is Immutable For Remove")
    <V extends MerkleNode & Keyed<Key>> void copyIsImmutableForRemove(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> copy = mm.copy();

        final Exception exception = assertThrows(
                MutabilityException.class, () -> mm.remove(provider.getDefaultKey()), "expected this method to fail");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "did not find expected error message");
        mm.release();
        copy.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Is Immutable For Replace")
    <V extends MerkleNode & Keyed<Key>> void originalIsImmutableForReplace(
            final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> mutableCopy = mm.copy();

        final Exception exception = assertThrows(
                MutabilityException.class,
                () -> mm.replace(provider.getDefaultKey(), null),
                "expected this method to fail");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "did not find expected error message");
        mm.release();
        mutableCopy.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Is Immutable For getForModify")
    <V extends MerkleNode & Keyed<Key>> void originalIsImmutableForGetForModify(
            final int size, final KeyValueProvider provider) {

        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> mutableCopy = mm.copy();

        final Exception exception = assertThrows(
                MutabilityException.class,
                () -> mm.getForModify(provider.getDefaultKey()),
                "expected this method to fail");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "did not find expected error message");
        mm.release();
        mutableCopy.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Original MM Is Immutable For Clear")
    <V extends MerkleNode & Keyed<Key>> void originalMMIsImmutableForClear(
            final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> copy = mm.copy();

        final Exception exception = assertThrows(MutabilityException.class, mm::clear, "expected this method to fail");
        assertEquals(
                "This operation is not permitted on an immutable object.",
                exception.getMessage(),
                "did not find expected error message");
        mm.release();
        copy.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Original MM Remains Immutable")
    <V extends MerkleNode & Keyed<Key>> void originalMMRemainsImmutable(
            final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final MerkleMap<Key, V> copy = mm.copy();

        final int newLimit = 2 * size;
        provider.insertIntoMap(size, newLimit, copy);

        assertEquals(newLimit, copy.getSize(), "expected map copy to match specified size");
        assertEquals(size, mm.getSize(), "expected map to match specified size");
        mm.release();
        copy.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Valid For Current Generation For Small Tree")
    void hashIsValidForCurrentGenerationForSmallTree() throws IOException {
        final int limit = 5;
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(0, limit, tree);
        cryptography.digestTreeSync(tree);

        final Hash expectedRootHash = tree.getHash();

        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(limit, mm);
        cryptography.digestTreeSync(mm);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, mm);

            assertEquals(expectedRootHash, mm.getRootHash(), "expected hash to match");
            mm.release();
        }
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Valid For Current Generation")
    void hashIsValidForCurrentGeneration() throws IOException {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(0, 100_001, tree);
        cryptography.digestTreeSync(tree);

        final Hash expectedRootHash = tree.getHash();

        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(100_001, mm);

        cryptography.digestTreeSync(mm);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, mm);

            assertEquals(expectedRootHash, mm.getRootHash(), "expected hash to match");
            mm.release();
        }
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Valid For Old Generation")
    void hashIsValidForOldGeneration() throws IOException {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(0, 10_001, tree);
        cryptography.digestTreeSync(tree);

        final Hash expectedRootHash = tree.getHash();

        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(10_001, mm);
        cryptography.digestTreeSync(mm);

        final MerkleMap<Key, Value> copiedMM = mm.copy();
        cryptography.digestTreeSync(copiedMM);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, copiedMM);

            assertEquals(expectedRootHash, copiedMM.getRootHash(), "expected hash to match");
            mm.release();
        }

        copiedMM.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Valid For Six Generations")
    void hashIsValidForSixGenerations() throws IOException {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(0, 10_001, tree);
        cryptography.digestTreeSync(tree);

        final Hash expectedRootHash = tree.getHash();

        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(10_001, mm);
        cryptography.digestTreeSync(mm);

        final MerkleMap<Key, Value> copiedMM01 = mm.copy();
        final MerkleMap<Key, Value> copiedMM02 = copiedMM01.copy();
        final MerkleMap<Key, Value> copiedMM03 = copiedMM02.copy();

        // Copy to expects the trees to be hashed
        cryptography.digestTreeSync(copiedMM01);
        cryptography.digestTreeSync(copiedMM02);
        cryptography.digestTreeSync(copiedMM03);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, copiedMM03);

            assertEquals(expectedRootHash, mm.getRootHash(), "expected hash to match");
            assertEquals(expectedRootHash, copiedMM01.getRootHash(), "expected hash to match");
            assertEquals(expectedRootHash, copiedMM02.getRootHash(), "expected hash to match");
            assertEquals(expectedRootHash, copiedMM03.getRootHash(), "expected hash to match");
            mm.release();
        }

        copiedMM01.release();
        copiedMM02.release();
        copiedMM03.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Valid For Six Generations With Insertions")
    void hashIsValidForSixGenerationsWithInsertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(0, 10_001, tree);
        cryptography.digestTreeSync(tree);

        final Hash expectedRootHash01 = tree.getHash();
        tree.invalidateHash();
        tree.getRoot().invalidateHash();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(10_001, 11_001, tree);
        cryptography.digestTreeSync(tree);
        final Hash expectedRootHash02 = tree.getHash();
        tree.invalidateHash();
        tree.getRoot().invalidateHash();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(11_001, 12_001, tree);
        cryptography.digestTreeSync(tree);
        final Hash expectedRootHash03 = tree.getHash();
        tree.invalidateHash();
        tree.getRoot().invalidateHash();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(12_001, 13_001, tree);
        cryptography.digestTreeSync(tree);
        final Hash expectedRootHash04 = tree.getHash();
        tree.invalidateHash();
        tree.getRoot().invalidateHash();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(13_001, 14_001, tree);
        cryptography.digestTreeSync(tree);
        final Hash expectedRootHash05 = tree.getHash();
        tree.invalidateHash();
        tree.getRoot().invalidateHash();
        MerkleMapTestUtil.insertIntoTreeWithoutBalanceCheck(14_001, 15_001, tree);
        cryptography.digestTreeSync(tree);
        final Hash expectedRootHash06 = tree.getHash();

        final MerkleMap<Key, Value> mm = new MerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(0, 10_001, mm);
        cryptography.digestTreeSync(mm);

        final MerkleMap<Key, Value> copiedMM01 = mm.copy();
        cryptography.digestTreeSync(mm);
        MerkleMapTestUtil.insertIntoMap(10_001, 11_001, copiedMM01);
        cryptography.digestTreeSync(copiedMM01);

        final MerkleMap<Key, Value> copiedMM02 = copiedMM01.copy();
        MerkleMapTestUtil.insertIntoMap(11_001, 12_001, copiedMM02);
        cryptography.digestTreeSync(copiedMM02);

        final MerkleMap<Key, Value> copiedMM03 = copiedMM02.copy();
        MerkleMapTestUtil.insertIntoMap(12_001, 13_001, copiedMM03);
        cryptography.digestTreeSync(copiedMM03);

        final MerkleMap<Key, Value> copiedMM04 = copiedMM03.copy();
        MerkleMapTestUtil.insertIntoMap(13_001, 14_001, copiedMM04);
        cryptography.digestTreeSync(copiedMM04);
        final MerkleMap<Key, Value> copiedMM05 = copiedMM04.copy();
        MerkleMapTestUtil.insertIntoMap(14_001, 15_001, copiedMM05);
        cryptography.digestTreeSync(copiedMM05);

        cryptography.digestTreeSync(mm);

        assertEquals(expectedRootHash01, mm.getRootHash(), "expected hash to match");
        assertEquals(expectedRootHash02, copiedMM01.getRootHash(), "expected hash to match");
        assertEquals(expectedRootHash03, copiedMM02.getRootHash(), "expected hash to match");
        assertEquals(expectedRootHash04, copiedMM03.getRootHash(), "expected hash to match");
        assertEquals(expectedRootHash05, copiedMM04.getRootHash(), "expected hash to match");
        assertEquals(expectedRootHash06, copiedMM05.getRootHash(), "expected hash to match");

        mm.release();
        copiedMM01.release();
        copiedMM02.release();
        copiedMM03.release();
        copiedMM04.release();
        copiedMM05.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Hash Is Null After Modify With Copy Test")
    void hashIsNullAfterModifyWithCopyTest() {
        final AccessibleMerkleMap<Key, Value> mm = new AccessibleMerkleMap<>();
        MerkleMapTestUtil.insertIntoMap(0, 1_024, mm);
        cryptography.digestTreeSync(mm);

        final Hash hash = mm.getRootHash();
        assertNotNull(hash, "expected non-null hash");
        assertNotNull(hash.getBytes(), "expected non-null hash data");
        final MerkleMap<Key, Value> copy01 = mm.copy();

        final Key key01 = new Key(0, 0, 0);
        final Value value01 = copy01.getForModify(key01);
        value01.setBalance(value01.getBalance() + 100);
        value01.setReceiverSignatureRequired(!value01.isReceiverSignatureRequired());
        value01.setReceiveThresholdValue(value01.getReceiveThresholdValue() + 100);
        value01.setSendThresholdValue(value01.getSendThresholdValue() + 100);

        final Value oldValue = copy01.replace(key01, value01);
        assertSame(oldValue, value01, "expected values to be the same object");

        final Hash treeHash = copy01.getHash();
        assertNull(treeHash, "expected null tree hash");
        mm.release();
        copy01.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Serialized And Deserialized FCQ Test")
    void serializedAndDeserializedFCQTest() throws IOException {
        MerkleMap<Key, FCQValue<TransactionRecord>> map = new MerkleMap<>();
        validateSerializeAndDeserialize(map);

        MerkleMap<Key, FCQValue<TransactionRecord>> copy = map.copy();
        map.release();
        map = copy;

        for (int index = 0; index < 100_000; index++) {
            final Key key = new Key(index, index, index);
            final long balance = 100_000;
            final byte[] content = new byte[100];
            MerkleMapTestUtil.random.nextBytes(content);
            final FCQValue<TransactionRecord> value = FCQValue.build(index, balance, content);
            map.put(key, value);
        }
        validateSerializeAndDeserialize(map);

        copy = map.copy();
        map.release();
        map = copy;

        for (int index = 0; index < 100_000; index++) {
            final Key key = new Key(index, index, index);
            final long newBalance = MerkleMapTestUtil.random.nextInt(1_000_000);
            final byte[] content = new byte[100];
            MerkleMapTestUtil.random.nextBytes(content);
            final FCQValue<TransactionRecord> value = map.get(key);
            final TransactionRecord newRecord =
                    new TransactionRecord(index + 1, newBalance, content, TransactionRecord.DEFAULT_EXPIRATION_TIME);
            final FCQValue<TransactionRecord> newValue = value.addRecord(newBalance, newRecord);
            map.replace(key, newValue);
        }

        cryptography.digestTreeSync(map);
        validateSerializeAndDeserialize(map);

        copy = map.copy();
        map.release();
        map = copy;

        for (int index = 0; index < 100_000; index++) {
            final Key fromKey = new Key(index, index, index);
            final FCQValue<TransactionRecord> fromValue = map.get(fromKey);
            final int toIndex = (index + 1) % 100_000;
            final Key toKey = new Key(toIndex, toIndex, toIndex);
            final FCQValue<TransactionRecord> toValue = map.get(toKey);

            final long transferAmount = MerkleMapTestUtil.random.nextInt(50_000);
            final byte[] newContent = new byte[100];
            MerkleMapTestUtil.random.nextBytes(newContent);
            final FCQValue<TransactionRecord> newFrom = fromValue.transferFrom(transferAmount, newContent);
            final FCQValue<TransactionRecord> newTo = toValue.transferTo(transferAmount, newContent);

            map.replace(fromKey, newFrom);
            map.replace(toKey, newTo);
        }

        cryptography.digestTreeSync(map);
        validateSerializeAndDeserialize(map);

        copy = map.copy();
        map.release();
        map = copy;

        for (int index = 0; index < 100_000; index++) {
            final Key key = new Key(index, index, index);
            final FCQValue<TransactionRecord> value = map.get(key);
            final FCQValue<TransactionRecord> newValue = value.deleteFirst();
            map.replace(key, newValue);
        }

        cryptography.digestTreeSync(map);
        validateSerializeAndDeserialize(map);

        copy = map.copy();
        map.release();
        map = copy;

        for (int index = 0; index < 100_000; index++) {
            final Key key = new Key(index, index, index);
            map.remove(key);
        }

        cryptography.digestTreeSync(map);
        validateSerializeAndDeserialize(map);
        map.release();
    }

    private void validateSerializeAndDeserialize(final MerkleMap<?, ?> map) throws IOException {

        cryptography.digestTreeSync(map);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, map);
            io.startReading();

            final MerkleMap<?, ?> deserializedMap = io.getInput().readMerkleTree(testDirectory, Integer.MAX_VALUE);

            cryptography.digestTreeSync(deserializedMap);

            assertEquals(map, deserializedMap, "expected deserialized map to match original");
            deserializedMap.release();
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Merkle serialization/deserialization")
    <V extends MerkleNode & Keyed<Key>> void serializeAndDeserializeAsMerkle(
            final int size, final KeyValueProvider provider) throws IOException {

        final MerkleMap<Key, V> mm = new MerkleMap<>();
        mm.setLabel("foobar");
        provider.insertIntoMap(0, size, mm);
        cryptography.digestTreeSync(mm);

        try (final ByteArrayOutputStream baseStream = new ByteArrayOutputStream();
                final MerkleDataOutputStream outputStream = new MerkleDataOutputStream(baseStream)) {
            outputStream.writeMerkleTree(testDirectory, mm);

            try (final MerkleDataInputStream inputStream =
                    new MerkleDataInputStream(new ByteArrayInputStream(baseStream.toByteArray()))) {

                final MerkleMap<Key, V> deserializeMM = inputStream.readMerkleTree(testDirectory, Integer.MAX_VALUE);
                assertEquals("foobar", deserializeMM.getLabel());
                cryptography.digestTreeSync(deserializeMM);

                assertEquals(mm, deserializeMM, "expected deserialized map to match the original");
                deserializeMM.release();
            }
        }

        mm.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Validates copy doesn't affect the original structure of MerkleBinaryTree")
    <V extends MerkleNode & Keyed<Key>> void deletesCopyAfterUsage(final int size, final KeyValueProvider provider) {
        MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        for (int keyIndex = 0; keyIndex < size; keyIndex++) {
            final MerkleMap<Key, V> copy = mm.copy();
            mm.release();
            copy.remove(new Key(keyIndex, keyIndex, keyIndex));
            mm = copy;
        }

        mm.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Makes sure that a MerkleMap in a merkle tree is properly deleted")
    void deletionTest() {

        final DummyMerkleNode root1 = MerkleTestUtils.buildLessSimpleTree();
        final DummyMerkleNode root2 = MerkleTestUtils.buildLessSimpleTree();

        final MerkleMap<Key, Value> map = new MerkleMap<>();
        assertEquals(0, map.getReservationCount(), "expected map to have only explicit reference");
        assertFalse(map.isDestroyed(), "expected map to not be destroyed");

        root1.asInternal().getChild(1).asInternal().setChild(2, map);
        assertEquals(1, map.getReservationCount(), "expected map to have one reference");
        assertFalse(map.isDestroyed(), "expected map not to be destroyed");

        root2.asInternal().getChild(1).asInternal().setChild(2, map);
        assertEquals(2, map.getReservationCount(), "expected map to have 2 references");
        assertFalse(map.isDestroyed(), "expected map to not be destroyed");

        root1.release();
        assertEquals(1, map.getReservationCount(), "expected map to have 1 reference");
        assertFalse(map.isDestroyed(), "expected map to not be destroyed");

        root2.release();
        assertEquals(-1, map.getReservationCount(), "reference count should be -1");
        assertTrue(map.isDestroyed(), "Expected map to be released");
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Throws If Deleted Test")
    void copyThrowsIfDeletedTest() {
        final MerkleMap<Key, Value> map = new MerkleMap<>();
        map.release();

        final Exception exception = assertThrows(ReferenceCountException.class, map::copy, "expected method to fail");
    }

    private static class KeyedDummyMerkleInternal extends PartialNaryMerkleInternal
            implements Keyed<MerkleLong>, MerkleInternal {

        public KeyedDummyMerkleInternal() {}

        private KeyedDummyMerkleInternal(final KeyedDummyMerkleInternal that) {
            for (int i = 0; i < that.getNumberOfChildren(); i++) {
                if (that.getChild(i) != null) {
                    setChild(i, that.getChild(i).copy());
                }
            }
        }

        @Override
        public long getClassId() {
            return 1234;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public KeyedDummyMerkleInternal copy() {
            return new KeyedDummyMerkleInternal(this);
        }

        @Override
        public MerkleLong getKey() {
            return getChild(3);
        }

        @Override
        public void setKey(final MerkleLong key) {
            setChild(3, key);
        }
    }

    /**
     * There was once a bug where copyTreeToLocation could fail if the number of children in a node being replaced was
     * fewer than the number of children of the replacing node. Before the fix to that problem, this test would fail.
     */
    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Copy Tree To Location Bug")
    void copyTreeToLocationBug() throws ConstructableRegistryException {

        ConstructableRegistry.getInstance()
                .registerConstructable(
                        new ClassConstructorPair(KeyedDummyMerkleInternal.class, KeyedDummyMerkleInternal::new));

        final MerkleMap<MerkleLong, KeyedDummyMerkleInternal> map = new MerkleMap<>();

        final int count = 1000;

        // Insert a bunch of elements. It is important that the values have more than 3 children.
        for (int i = 0; i < count; i++) {
            final KeyedDummyMerkleInternal value = new KeyedDummyMerkleInternal();
            value.setChild(0, new DummyMerkleInternal("A"));
            value.setChild(1, new DummyMerkleInternal("B"));
            value.setChild(2, new DummyMerkleInternal("C"));

            map.put(new MerkleLong(i), value);
        }

        // Delete all the elements. Causes the tree to shrink, provoking the bug (now fixed)
        for (int i = 0; i < count; i++) {
            map.remove(new MerkleLong(i));
        }

        map.release();
    }

    @Test
    @Tag(TestComponentTags.MMAP)
    @DisplayName("Null Not Supported Test")
    void nullNotSupportedTest() {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> map = new MerkleMap<>();

        assertThrows(
                NullPointerException.class,
                () -> map.put(null, new KeyedMerkleLong<>(0)),
                "null key should throw exception");
        assertThrows(
                NullPointerException.class,
                () -> map.put(new SerializableLong(0), null),
                "null value should throw exception");

        map.release();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Entry Set Test")
    <V extends MerkleNode & Keyed<Key>> void entrySetTest(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final Set<Map.Entry<Key, V>> entrySet = mm.entrySet();

        assertEquals(size, entrySet.size(), "Entry set size should match merkle map size");

        int iterationCount = 0;

        for (final Map.Entry<Key, V> mapEntry : entrySet) {
            ++iterationCount;
        }

        assertEquals(size, iterationCount, "Iterating through the entire set should visit every element");
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Entry Set Iterator")
    <V extends MerkleNode & Keyed<Key>> void entrySetIterator(final int size, final KeyValueProvider provider) {
        final MerkleMap<Key, V> mm = new MerkleMap<>();
        provider.insertIntoMap(0, size, mm);

        final Iterator<Entry<Key, V>> entrySetIterator = mm.entrySet().iterator();

        int iterationCount = 0;

        while (entrySetIterator.hasNext()) {
            ++iterationCount;
            entrySetIterator.next();
        }

        assertEquals(size, iterationCount, "Iterating should visit every element");
    }

    @Test
    @DisplayName("Entry Set Iterator Remove")
    void entrySetIteratorRemove() {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm = new MerkleMap<>();
        mm.put(new SerializableLong(), new KeyedMerkleLong<>());

        final Iterator<Entry<SerializableLong, KeyedMerkleLong<SerializableLong>>> entrySetIterator =
                mm.entrySet().iterator();

        assertThrows(
                UnsupportedOperationException.class,
                entrySetIterator::remove,
                "Removing from the entry set is unsupported");
    }

    @Test
    @DisplayName("Entry Set Unsupported Equals")
    void entrySetUnsupportedEquals() {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm = new MerkleMap<>();
        final Set<Map.Entry<SerializableLong, KeyedMerkleLong<SerializableLong>>> entrySet1 = mm.entrySet();
        final Set<Map.Entry<SerializableLong, KeyedMerkleLong<SerializableLong>>> entrySet2 = mm.entrySet();

        assertThrows(
                UnsupportedOperationException.class,
                () -> entrySet1.equals(entrySet2),
                "Equals shouldn't be supported");
    }

    @Test
    @DisplayName("Entry Set Unsupported HashCode")
    void entrySetUnsupportedHashCode() {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm = new MerkleMap<>();
        final Set<Map.Entry<SerializableLong, KeyedMerkleLong<SerializableLong>>> entrySet = mm.entrySet();

        assertThrows(UnsupportedOperationException.class, entrySet::hashCode, "HashCode shouldn't be supported");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 10, 100, 125, 128, 135, 1_000, 10_000, 100_000})
    @DisplayName("rebuild() test")
    void rebuildTest(final int size) {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm = new MerkleMap<>();

        for (int i = 0; i < size; i++) {
            mm.put(new SerializableLong(i), new KeyedMerkleLong<>(i * 2L));
        }

        mm.getIndex().clear();
        assertEquals(0, mm.getIndex().size(), "index should be cleared");

        mm.rebuild();
        assertEquals(size, mm.getIndex().size(), "unexpected size");

        for (int i = 0; i < size; i++) {
            final SerializableLong key = new SerializableLong(i);
            final KeyedMerkleLong<SerializableLong> expected = new KeyedMerkleLong<>(i * 2L);
            expected.setKey(key);
            assertEquals(expected, mm.get(key), "unexpected value");
        }
    }

    @Test
    @DisplayName("Label Test")
    void labelTest() throws IOException {
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm1 = new MerkleMap<>();
        assertEquals("", mm1.getLabel());

        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm2 = mm1.copy();
        assertEquals("", mm1.getLabel());
        assertEquals("", mm2.getLabel());

        mm2.setLabel("foobar");
        assertEquals("", mm1.getLabel());
        assertEquals("foobar", mm2.getLabel());

        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm3 = mm2.copy();
        assertEquals("", mm1.getLabel());
        assertEquals("foobar", mm2.getLabel());
        assertEquals("foobar", mm3.getLabel());

        mm3.setLabel("foobarbaz");
        assertEquals("", mm1.getLabel());
        assertEquals("foobar", mm2.getLabel());
        assertEquals("foobarbaz", mm3.getLabel());

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final MerkleDataOutputStream out = new MerkleDataOutputStream(byteOut);

        out.writeMerkleTree(testDirectory, mm3);

        final MerkleDataInputStream in = new MerkleDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
        final MerkleMap<SerializableLong, KeyedMerkleLong<SerializableLong>> mm4 =
                in.readMerkleTree(testDirectory, 1000);

        assertEquals("foobarbaz", mm4.getLabel());

        mm1.release();
        mm2.release();
        mm3.release();
        mm4.release();
    }
}
