// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.Value;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.merkle.tree.MerkleBinaryTree;
import com.swirlds.merkle.tree.MerkleTreeInternalNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MerkleBinaryTree Tests")
class MerkleBinaryTreeTests {

    private static final long[] ACCOUNT_ID = {1L, 2L, 3L};
    private static final Random RANDOM = new Random();
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    public static void insertIntoTree(
            final int startIndex,
            final int endIndex,
            final MerkleBinaryTree<Value> tree,
            final Consumer<Value> updateCache) {

        for (int index = startIndex; index < endIndex; index++) {
            final Value value = Value.newBuilder()
                    .setBalance(RANDOM.nextLong())
                    .setReceiveThresholdValue(RANDOM.nextLong())
                    .setSendThresholdvalue(RANDOM.nextLong())
                    .setReceiveSignatureRequired(RANDOM.nextBoolean())
                    .build();
            final Key key = new Key(new long[] {index, index, index});
            value.setKey(key);
            tree.insert(value, updateCache);
        }
    }

    public static Map<Key, Value> insertIntoTreeWithTrackedLeaves(
            final int startIndex, final int endIndex, final MerkleBinaryTree<Value> tree) {

        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        for (int index = startIndex; index < endIndex; index++) {
            final Value value = Value.newBuilder()
                    .setBalance(RANDOM.nextLong())
                    .setReceiveThresholdValue(RANDOM.nextLong())
                    .setSendThresholdvalue(RANDOM.nextLong())
                    .setReceiveSignatureRequired(RANDOM.nextBoolean())
                    .build();
            final Key key = new Key(new long[] {index, index, index});
            value.setKey(key);
            tree.insert(value, updateCache);
            cache.put(value.getKey(), value);
        }

        return cache;
    }

    public static void insertIntoTreeWithSimpleData(
            final int startIndex, final int endIndex, final MerkleBinaryTree<Value> tree) {
        for (int index = startIndex; index < endIndex; index++) {
            final Key key = new Key(new long[] {index, index, index});
            final Value value = new Value(index, index, index, true);
            value.setKey(key);
            tree.insert(value, MerkleBinaryTreeTests::updateCache);
        }
    }

    /**
     * Copies the given leaf and updates the cache.
     */
    public static <V extends MerkleNode & Keyed<Key>> Consumer<V> buildCacheUpdater(final Map<Key, V> cache) {

        return leaf -> cache.put(leaf.getKey(), leaf);
    }

    /**
     * A no-op implementation of an updateCache method. Useful for tests where we dont' care about a cache
     */
    public static <V extends MerkleNode> void updateCache(final V original) {}

    @BeforeAll
    void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero");
    }

    protected Stream<Arguments> buildSizeArguments() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(100));
        arguments.add(Arguments.of(1_000));
        arguments.add(Arguments.of(10_000));
        return arguments.stream();
    }

    protected Stream<Arguments> buildSizeArgumentsToCheckBalance() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(1));
        arguments.add(Arguments.of(2));
        arguments.add(Arguments.of(3));
        arguments.add(Arguments.of(4));
        arguments.add(Arguments.of(7));
        arguments.add(Arguments.of(8));
        arguments.add(Arguments.of(101));
        arguments.add(Arguments.of(512));
        arguments.add(Arguments.of(1_024));
        return arguments.stream();
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count For One Leaf In Tree")
    void referenceCountForOneLeafInTree() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value01 = Value.newBuilder().build();
        final Key key = new Key(ACCOUNT_ID);
        value01.setKey(key);
        assertEquals(0, value01.getReservationCount(), "leaf01 hasn't been assigned to a parent");

        tree.insert(value01, MerkleBinaryTreeTests::updateCache);

        assertEquals(1, value01.getReservationCount(), "leaf01 was assigned a parent");

        final Value value02 = Value.newBuilder().build();
        value02.setKey(new Key(ACCOUNT_ID));
        tree.update(value01, value02);

        assertEquals(1, value02.getReservationCount(), "leaf02 was assigned a parent");
        assertEquals(-1, value01.getReservationCount(), "leaf01's was removed during update");

        assertTrue(value01.isDestroyed(), "leaf01 was destroyed during update");

        assertFalse(value02.isDestroyed(), "leaf02 is still in the tree");

        tree.release();

        assertTrue(value02.isDestroyed(), "leaf02 was destroyed during tree's release");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count For One Leaf In Tree With Copy")
    void referenceCountForOneLeafInTreeWithCopy() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value1 = Value.newBuilder().build();
        final Value value2 = Value.newBuilder().build();
        final Key key = new Key(ACCOUNT_ID);
        value1.setKey(key);

        assertEquals(0, value1.getReservationCount(), "leaf01 hasn't been assigned to a parent");

        tree.insert(value1, MerkleBinaryTreeTests::updateCache);

        final MerkleBinaryTree<Value> copy = tree.copy();
        assertEquals(2, value1.getReservationCount(), "leaf01 has its original parent and the copy");
        value2.setKey(key.copy());
        copy.update(value1, value2);

        assertEquals(1, value2.getReservationCount(), "leaf02 was inserted into a tree");
        assertEquals(1, value1.getReservationCount(), "leaf01 is not referenced by the copy");

        tree.release();
        assertEquals(-1, value1.getReservationCount(), "leaf01 was destroyed by tree's release");
        assertEquals(1, value2.getReservationCount(), "leaf02 reference count hasn't been affected");

        copy.release();
        assertEquals(-1, value2.getReservationCount(), "leaf02 was destroyed by copy's release");
    }

    @ParameterizedTest
    @MethodSource("buildSizeArguments")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count With Multiple Leaves")
    void referenceCountWithMultipleLeaves(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);

        this.validateAliveLeaves(size, leaves);

        tree.release();

        this.validateDeletedLeaves(leaves);
    }

    @ParameterizedTest
    @MethodSource("buildSizeArguments")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count With Insertions And Updates")
    void referenceCountWithInsertionsAndUpdates(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);

        final Map<Key, Value> newLeaves = new HashMap<>();
        for (Key key : leaves.keySet()) {

            // Keys in the cache may have been destroyed
            key = key.copy();

            final Value value = Value.newBuilder().build();
            newLeaves.put(key, value);
            tree.update(leaves.get(key), value);
        }

        this.validateAliveLeaves(size, newLeaves);
        this.validateDeletedLeaves(leaves);

        tree.release();
        this.validateDeletedLeaves(newLeaves);
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count Delete With One Leaf")
    void referenceCountDeleteWithOneLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, 1, tree);
        this.validateAliveLeaves(1, leaves);

        for (final Value leaf : leaves.values()) {
            tree.delete(leaf, MerkleBinaryTreeTests::updateCache);
        }

        this.validateDeletedLeaves(leaves);
    }

    @ParameterizedTest
    @MethodSource("buildSizeArguments")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count With Insertions, Updates, And Deletes")
    void referenceCountWithInsertionsAndUpdatesAndDeletes(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);

        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        for (final Key key : leaves.keySet()) {
            final Value value = Value.newBuilder().build();
            value.setKey(key.copy());
            cache.put(key, value);
            tree.update(leaves.get(key), value);
        }

        this.validateAliveLeaves(size, cache);
        this.validateDeletedLeaves(leaves);

        for (final Key key : cache.keySet()) {
            tree.delete(cache.get(key), updateCache);
        }

        this.validateDeletedLeaves(cache);
    }

    @ParameterizedTest
    @MethodSource("buildSizeArguments")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count With Multiple Leaves With Copy")
    void referenceCountWithMultipleLeavesWithCopy(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);
        final MerkleBinaryTree<Value> copyTree = tree.copy();

        this.validateAliveLeaves(size, leaves);

        copyTree.release();

        this.validateAliveLeaves(size, leaves);

        tree.release();

        this.validateDeletedLeaves(leaves);
    }

    @ParameterizedTest
    @MethodSource("buildSizeArguments")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Reference Count WIth Multiple Copies")
    void referenceCountWithMultipleCopies(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);
        final MerkleBinaryTree<Value> copyTree01 = tree.copy();
        final MerkleBinaryTree<Value> copyTree02 = copyTree01.copy();
        final MerkleBinaryTree<Value> copyTree03 = copyTree02.copy();
        final MerkleBinaryTree<Value> copyTree04 = copyTree03.copy();
        final MerkleBinaryTree<Value> copyTree05 = copyTree04.copy();

        this.validateAliveLeaves(size, leaves);

        tree.release();

        this.validateAliveLeaves(size, leaves);

        copyTree01.release();
        this.validateAliveLeaves(size, leaves);

        copyTree02.release();
        this.validateAliveLeaves(size, leaves);

        copyTree03.release();
        this.validateAliveLeaves(size, leaves);

        copyTree04.release();
        this.validateAliveLeaves(size, leaves);

        copyTree05.release();
        this.validateDeletedLeaves(leaves);
    }

    @ParameterizedTest
    @MethodSource("buildSizeArgumentsToCheckBalance")
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Inserts Leaves")
    void insertsLeaves(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        insertIntoTree(0, size, tree, MerkleBinaryTreeTests::updateCache);

        assertEquals(size, tree.size(), "Size should match the number of inserted elements");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Clear After 1024 Insertions")
    void clearAfter1024Insertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        insertIntoTree(0, 1_024, tree, MerkleBinaryTreeTests::updateCache);
        tree.clear();

        assertEquals(0, tree.size(), "After a clear, no elements should be in the tree");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces First Leaf")
    void replacesFirstLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value = Value.newBuilder().build();
        final Key key = new Key(ACCOUNT_ID);
        value.setKey(key);
        tree.insert(value, MerkleBinaryTreeTests::updateCache);

        final Value newValue = Value.newBuilder()
                .setBalance(1L)
                .setReceiveThresholdValue(100L)
                .setSendThresholdvalue(100L)
                .setReceiveSignatureRequired(true)
                .build();
        newValue.setKey(key.copy());

        tree.update(value, newValue);

        assertEquals(tree.getRoot().getLeft(), newValue, "nodes should be equal");
        assertEquals(1, tree.size(), "Tree is the incorrect size");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces Null Leaf")
    void replacesNullLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value leaf = this.createSimpleLeaf();

        assertThrows(
                IllegalArgumentException.class, () -> tree.update(null, leaf), "Update does not support null leaves");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces Invalid Leaf")
    void replacesInvalidLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value leaf = this.createSimpleLeaf();

        assertThrows(
                IllegalStateException.class, () -> tree.update(leaf, leaf), "The tree is empty. No leaf to replace");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces With Null Leaf")
    void replacesWithNullLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value = Value.newBuilder().build();
        final Key key = new Key(ACCOUNT_ID);
        value.setKey(key);
        tree.insert(value, MerkleBinaryTreeTests::updateCache);

        assertThrows(
                IllegalArgumentException.class, () -> tree.update(value, null), "Update does not support null leaves");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces First Leaf After Second Insertion")
    void replacesFirstLeafAfterSecondInsertion() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(ACCOUNT_ID);
        value01.setKey(key01);
        tree.insert(value01, MerkleBinaryTreeTests::updateCache);

        final Value value02 = Value.newBuilder().build();
        final Key key02 = new Key(new long[] {1L, 2L, 2L});
        value02.setKey(key02);
        tree.insert(value02, MerkleBinaryTreeTests::updateCache);

        final Value newValue = Value.newBuilder().setBalance(1L).build();
        newValue.setKey(key02);

        tree.update(value01, newValue);

        assertEquals(tree.getRoot().getLeft(), newValue, "node should be equal");
        assertEquals(2, tree.size(), "tree is the incorrect size");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces First Leaf After Eighth Insertions")
    void replacesFirstLeafAfterEightInsertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(ACCOUNT_ID);
        value01.setKey(key01);
        tree.insert(value01, updateCache);
        cache.put(key01, value01);

        insertIntoTree(0, 7, tree, updateCache);

        final Value newValue = Value.newBuilder().setBalance(1L).build();
        newValue.setKey(key01.copy());

        tree.update(cache.get(key01), newValue);

        assertEquals(tree.getNodeAtRoute(newValue.getRoute()), newValue, "leaf route should lead to itself");
        assertEquals(8, tree.size(), "One more element was inserted after inserting 7");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces Last Leaf After Eighth Insertion")
    void replacesLastLeafAfterEightInsertion() {
        final int sizeOfTree = 7;
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        insertIntoTree(0, sizeOfTree, tree, MerkleBinaryTreeTests::updateCache);

        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(ACCOUNT_ID);
        value01.setKey(key01);
        tree.insert(value01, MerkleBinaryTreeTests::updateCache);

        final Value newValue = Value.newBuilder().setBalance(13L).build();
        newValue.setKey(key01.copy());

        tree.update(value01, newValue);
        final Value rightMostLeaf = tree.getRightMostLeaf();

        assertEquals(sizeOfTree + 1, tree.size(), "One more element was inserted after the batch insertion");
        assertEquals(tree.getNodeAtRoute(newValue.getRoute()), newValue, "leaf route should lead to itself");
        assertEquals(newValue, rightMostLeaf, "The updated leaf is the right most leaf");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces Last Leaf After 1K Insertions")
    void replacesLastLeafAfter16KInsertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        insertIntoTree(0, 1023, tree, MerkleBinaryTreeTests::updateCache);

        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(ACCOUNT_ID);
        value01.setKey(key01);
        tree.insert(value01, MerkleBinaryTreeTests::updateCache);

        final Value newValue = Value.newBuilder().setBalance(1L).build();
        newValue.setKey(key01.copy());

        tree.update(value01, newValue);

        final Value rightMostLeaf = tree.getRightMostLeaf();

        assertEquals(tree.getNodeAtRoute(newValue.getRoute()), newValue, "leaf route should lead to itself");
        assertEquals(newValue, rightMostLeaf, "The updated leaf is the right most leaf due to the size of the tree");
        assertEquals(1024, tree.size(), "One more element was inserted after the batch insertion");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Replaces Second Leaf")
    void replacesSecondLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(ACCOUNT_ID);
        value01.setKey(key01);
        tree.insert(value01, updateCache);
        cache.put(key01, value01);

        final Value value02 = Value.newBuilder().build();
        final Key key02 = new Key(new long[] {1L, 2L, 2L});
        value02.setKey(key01.copy());
        tree.insert(value02, updateCache);
        cache.put(key02, value02);

        final Value newValue = Value.newBuilder().setBalance(1L).build();
        newValue.setKey(key02.copy());

        tree.update(cache.get(key02), newValue);

        assertEquals(tree.getRoot().getRight(), newValue, "leaf should be the right child of the root");
        assertEquals(2, tree.size(), "Only two elements were inserted into the tree");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Inserts And Deletes One Leaf")
    void insertsAndDeletesOneLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value = Value.newBuilder().build();
        final Key key = new Key(new long[] {0, 0, 0});
        value.setKey(key);

        tree.insert(value, MerkleBinaryTreeTests::updateCache);
        tree.delete(value, MerkleBinaryTreeTests::updateCache);

        assertEquals(0, tree.size(), "The only element from the tree was deleted");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Inserts And Deletes First Leaf After Second Insertion")
    void insertsAndDeletesFirstLeafAfterSecondInsertion() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value leaf01 = this.createSimpleLeaf();
        tree.insert(leaf01, MerkleBinaryTreeTests::updateCache);

        final Value value02 = Value.newBuilder().build();
        final Key key02 = new Key(new long[] {1, 1, 1});
        value02.setKey(key02);

        tree.insert(value02, MerkleBinaryTreeTests::updateCache);

        tree.delete(leaf01, MerkleBinaryTreeTests::updateCache);

        assertEquals(1, tree.size(), "One out of two elements from the tree was deleted");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes Invalid Leaf")
    void deletesInvalidLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value leaf01 = this.createSimpleLeaf();

        assertThrows(
                IllegalStateException.class,
                () -> tree.delete(leaf01, MerkleBinaryTreeTests::updateCache),
                "The tree is empty. No leaf to delete");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes Null Leaf")
    void deletesNullLeaf() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        assertThrows(
                IllegalArgumentException.class,
                () -> tree.delete(null, MerkleBinaryTreeTests::updateCache),
                "Delete does not support null leaves");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes Second Leaf After Second Insertion")
    void deletesSecondLeafAfterSecondInsertion() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value leaf01 = this.createSimpleLeaf();
        tree.insert(leaf01, MerkleBinaryTreeTests::updateCache);

        final Value value02 = Value.newBuilder().build();
        final Key key02 = new Key(new long[] {1, 1, 1});
        value02.setKey(key02);

        tree.insert(value02, MerkleBinaryTreeTests::updateCache);

        tree.delete(value02, MerkleBinaryTreeTests::updateCache);

        assertEquals(1, tree.size(), "One out of two elements from the tree was deleted");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes On Empty Tree")
    void deletesOnEmptyTree() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(new long[] {0, 0, 0});
        value01.setKey(key01);

        assertThrows(
                IllegalStateException.class,
                () -> tree.delete(value01, MerkleBinaryTreeTests::updateCache),
                "The tree is empty. No leaf to delete");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes First Leaf After 4 Insertions")
    void deletesFirstLeafAfter4Insertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        final Value leaf01 = this.createSimpleLeaf();
        cache.put(leaf01.getKey(), leaf01);

        tree.insert(leaf01, updateCache);
        insertIntoTree(1, 4, tree, updateCache);

        tree.delete(cache.get(leaf01.getKey()), updateCache);

        assertEquals(3, tree.size(), "One out of four elements from the tree was deleted");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes First Leaf After 8 Insertions")
    void deletesFirstLeafAfter8Insertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> cache = new HashMap<>();
        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(new long[] {0, 0, 0});
        value01.setKey(key01);
        tree.insert(value01, updateCache);
        cache.put(key01, value01);

        insertIntoTree(1, 8, tree, updateCache);

        tree.delete(cache.get(key01), updateCache);

        assertEquals(7, tree.size(), "One out of eight elements from the tree was deleted");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes Last Leaf After 3 Insertions")
    void deletesLastLeafAfter3Insertions() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();

        insertIntoTree(0, 2, tree, MerkleBinaryTreeTests::updateCache);

        final Value value03 = Value.newBuilder().build();
        final Key key03 = new Key(new long[] {0, 0, 0});
        value03.setKey(key03);
        tree.insert(value03, MerkleBinaryTreeTests::updateCache);

        tree.delete(value03, MerkleBinaryTreeTests::updateCache);

        assertEquals(2, tree.size(), "One out of three elements from the tree was deleted");
    }

    private void assertOnDeletionAsQueue(long size, final Map<Key, Value> cache, final MerkleBinaryTree<Value> tree) {

        final Consumer<Value> updateCache = buildCacheUpdater(cache);

        for (final Key key : cache.keySet()) {
            final Value leaf = cache.get(key);
            tree.delete(leaf, updateCache);
            size--;
            assertEquals(size, tree.size(), "The size of the tree decreases as we keep deleting elements");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 1_024 /*, 8_000, 16_384 */})
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes Elements")
    void deletesElements(final int size) {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, size, tree);

        this.assertOnDeletionAsQueue(size, leaves, tree);
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Deletes 1K Elements Randomly")
    void deletes16KElementsRandomly() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Map<Key, Value> leaves = insertIntoTreeWithTrackedLeaves(0, 1024, tree);
        final Random random = new Random();

        final List<Key> keys = new LinkedList<>(leaves.keySet());

        while (!keys.isEmpty()) {

            final int elementIndex = random.nextInt(keys.size());
            final Key key = keys.remove(elementIndex);
            final Value leaf = leaves.get(key);

            tree.delete(leaf, u -> leaves.put(u.getKey(), u));

            assertEquals(keys.size(), tree.size(), "number of keys should match tree size");
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Inserts 2K Leaves And Iterates Over Fast Serializables")
    void inserts8MLeavesAndIteratesOverFastSerializables() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        insertIntoTree(0, 2048, tree, MerkleBinaryTreeTests::updateCache);

        final Iterator<Value> merkleLeaves = tree.iterator();
        int counter = 0;
        while (merkleLeaves.hasNext()) {
            merkleLeaves.next();
            counter += 2;
        }

        assertEquals(4096, counter, "There are twice keys and values the number of MerklePair");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Original FCMTree Is Immutable")
    void copyIsImmutable() {
        final int sizeOfTree = 1_000;
        final MerkleBinaryTree<Value> tree01 = new MerkleBinaryTree<>();
        insertIntoTreeWithSimpleData(0, sizeOfTree, tree01);
        final MerkleBinaryTree<Value> tree02 = new MerkleBinaryTree<>();
        insertIntoTreeWithSimpleData(0, sizeOfTree, tree02);

        final MerkleBinaryTree<Value> copy01 = tree01.copy();
        insertIntoTreeWithSimpleData(1_000, 1_500, copy01);

        final Iterator<Value> tree01Iterator = tree01.iterator();
        final Iterator<Value> tree02Iterator = tree02.iterator();

        while (tree01Iterator.hasNext() && tree02Iterator.hasNext()) {
            final Value element01 = tree01Iterator.next();
            final Value element02 = tree02Iterator.next();
            assertEquals(element01, element02, "The elements were generated the same");
        }

        assertEquals(
                tree01Iterator.hasNext(),
                tree02Iterator.hasNext(),
                "No elements should be available in either iterator");

        final MerkleCryptography cryptography = TestMerkleCryptoFactory.getInstance();
        cryptography.digestTreeSync(tree01);
        cryptography.digestTreeSync(tree02);

        assertEquals(tree02.getHash(), tree01.getHash(), "Trees have same data. Hashes should match");
    }

    private boolean isPathUnique(final MerkleNode root, final MerkleRoute route) {
        Iterator<MerkleNode> iterator = new MerkleRouteIterator(root, route);
        AtomicBoolean isPathUnique = new AtomicBoolean(true);
        iterator.forEachRemaining((MerkleNode node) -> {
            if (node.getReservationCount() > 1) {
                isPathUnique.set(false);
            }
        });

        return isPathUnique.get();
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Is Path Unique One Leaf Test")
    void isPathUniqueOneLeafTest() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        final Value value01 = Value.newBuilder().build();
        final Key key = new Key(ACCOUNT_ID);
        value01.setKey(key);
        assertEquals(0, value01.getReservationCount(), "leaf01 has no parent assigned");
        tree.insert(value01, MerkleBinaryTreeTests::updateCache);

        assertEquals(1, tree.getRoot().getReservationCount(), "leaf01 was inserted into tree");
        assertTrue(isPathUnique(tree, tree.getRoot().getRoute()), "the path should have been unique");

        assertEquals(1, value01.getReservationCount(), "Still only one parent is assigned to leaf01");
        assertTrue(isPathUnique(tree, value01.getRoute()), "the path should have been unique");

        final MerkleBinaryTree<Value> copy = tree.copy();
        assertEquals(2, value01.getReservationCount(), "leaf01 has two parents");

        assertTrue(isPathUnique(tree, tree.getRoot().getRoute()), "the path should have been unique");
        assertFalse(isPathUnique(tree, value01.getRoute()), "the path should have been unique");

        final Value value02 = value01.copy();
        value02.setKey(key.copy());
        copy.update(value01, value02);

        assertEquals(1, value02.getReservationCount(), "leaf02 was inserted into copy");
        assertEquals(1, value01.getReservationCount(), "leaf01 now has only one parent");

        assertTrue(isPathUnique(tree, value01.getRoute()), "the path should have been unique");
        assertTrue(isPathUnique(tree, value02.getRoute()), "the path should have been unique");

        tree.release();
        assertEquals(-1, value01.getReservationCount(), "leaf01 was destroyed due to tree's release");
        assertEquals(1, value02.getReservationCount(), "leaf02 still has only one parent");

        assertTrue(isPathUnique(tree, value01.getRoute()), "the path should have been unique");
        assertTrue(isPathUnique(tree, value02.getRoute()), "the path should have been unique");

        copy.release();
        assertEquals(-1, value02.getReservationCount(), "leaf02 was destroyed due to copy's destroyed");
        assertTrue(isPathUnique(tree, value02.getRoute()), "the path should have been unique");
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Is Path Unique Multi Leaves Test")
    void isPathUniqueMultiLeavesTest() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        insertIntoTree(0, 1024, tree, MerkleBinaryTreeTests::updateCache);
        final MerkleTreeInternalNode root = tree.getRoot();
        assertEquals(1, root.getReservationCount(), "Root has one parent");
        assertTrue(isPathUnique(tree, root.getRoute()), "the path should have been unique");
        final MerkleTreeInternalNode rootLChild = root.getLeft();
        assertEquals(1, rootLChild.getReservationCount(), "left child has only root as parent");
        assertTrue(isPathUnique(tree, rootLChild.getRoute()), "the path should have been unique");
        final Value leaf = tree.getRightMostLeaf();
        assertEquals(1, leaf.getReservationCount(), "leaf has only root as parent");
        assertTrue(isPathUnique(tree, leaf.getRoute()), "the path should have been unique");

        final MerkleBinaryTree<Value> tree01 = tree.copy();
        final MerkleBinaryTree<Value> tree02 = tree01.copy();
        tree02.copy();

        // after three copies, the refCount of root and rightMostLeaf are not changed; the refCount of root's left
        // child increases by 3
        assertEquals(1, root.getReservationCount(), "root has only one parent");
        assertTrue(isPathUnique(tree, root.getRoute()), "the path should have been unique");
        // root's left child doesn't have unique path because its refCount is 4
        assertEquals(4, rootLChild.getReservationCount(), "Left child has 4 parents due to copies");
        assertFalse(isPathUnique(tree, rootLChild.getRoute()), "the path should have been unique");
        // rightmost leaf doesn't have unique path because there is one node in the path from this leaf to root whose
        // refCount is 4
        assertEquals(1, leaf.getReservationCount(), "Only one parent has been assigned to the leaf");
        assertFalse(isPathUnique(tree, leaf.getRoute()), "the path should have been unique");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 102, 1024 /* 1_000_000, 1_048_576 */})
    @Tag(TestComponentTags.MERKLETREE)
    @DisplayName("Serialize And Deserialize")
    void serializeAndDeserialize(final int size) throws IOException {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        insertIntoTree(0, size, tree, MerkleBinaryTreeTests::updateCache);

        try (final InputOutputStream io = new InputOutputStream()) {
            io.getOutput().writeMerkleTree(testDirectory, tree);

            io.startReading();
            final MerkleBinaryTree<Value> deserializedTree =
                    io.getInput().readMerkleTree(testDirectory, Integer.MAX_VALUE);

            assertEquals(tree.size(), deserializedTree.size(), "Size should match after deserialization");

            final Iterator<Value> leaves = tree.iterator();
            final Iterator<Value> deserializedLeaves = deserializedTree.iterator();

            final int counter = assertOnIterables(leaves, deserializedLeaves);

            assertEquals(leaves.hasNext(), deserializedLeaves.hasNext(), "No more leaves on each iterator");
            assertEquals(size, counter, "The number of leaves should match the size of the tree");
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLETREE)
    void copyThrowsIfDeletedTest() {
        final MerkleBinaryTree<Value> tree = new MerkleBinaryTree<>();
        tree.release();

        final Exception exception =
                assertThrows(ReferenceCountException.class, tree::copy, "expected this method to fail");
        assertEquals(
                "This operation is not permitted on a destroyed object.",
                exception.getMessage(),
                "The message should be about the object being destroyed");
    }

    private Value createSimpleLeaf() {
        final Value value01 = Value.newBuilder().build();
        final Key key01 = new Key(new long[] {0, 0, 0});
        value01.setKey(key01);
        return value01;
    }

    private int assertOnIterables(
            final Iterator<? extends MerkleNode> iter1, final Iterator<? extends MerkleNode> iter2) {
        int counter = 0;
        while (iter1.hasNext() && iter2.hasNext()) {
            final MerkleNode leftObject = iter1.next();
            final MerkleNode rightObject = iter2.next();
            assertEquals(leftObject, rightObject, "Objects should be equal");
            counter++;
        }

        return counter;
    }

    private void validateDeletedLeaves(final Map<Key, Value> leaves) {
        for (final Value leaf : leaves.values()) {
            assertEquals(
                    -1,
                    leaf.getReservationCount(),
                    String.format("(Key: %s) should have -1 references", leaf.getKey()));
            assertTrue(leaf.isDestroyed(), "Value must be deleted");
        }
    }

    private void validateAliveLeaves(final int expectedNumberOfLeaves, final Map<Key, Value> leaves) {
        assertEquals(expectedNumberOfLeaves, leaves.size(), "Number of leaves remains the same");

        for (final Value leaf : leaves.values()) {
            assertEquals(1, leaf.getReservationCount(), "Should have 1 reference");
            assertFalse(leaf.isDestroyed(), "Value shouldn't be deleted");
        }
    }
}
