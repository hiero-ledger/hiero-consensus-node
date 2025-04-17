// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.common.test.fixtures.set.RandomAccessHashSet;
import com.swirlds.common.test.fixtures.set.RandomAccessSet;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Random VirtualMap MerkleDb Reconnect Tests")
class RandomVirtualMapReconnectTests extends VirtualMapReconnectTestBase {

    // used to convert between key as long to key as String
    public static final int LETTER_COUNT = 26;
    public static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    public static final int ZZZZZ = 26 * 26 * 26 * 26 * 26; // key value corresponding to five Z's (plus 1)

    @Override
    protected VirtualDataSourceBuilder createBuilder() throws IOException {
        // The tests create maps with identical names. They would conflict with each other in the default
        // MerkleDb instance, so let's use a new (temp) database location for every run
        final Path defaultVirtualMapPath = LegacyTemporaryFileBuilder.buildTemporaryFile(CONFIGURATION);
        MerkleDb.setDefaultPath(defaultVirtualMapPath);
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());
        return new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
    }

    public String randomWord(final Random random, final int maximumKeySize) {
        final int key = random.nextInt(maximumKeySize);
        return keyToWord(key);
    }

    public String keyToWord(int key) {
        final int fifth = key % LETTER_COUNT;
        key = (key - fifth) / LETTER_COUNT;
        final int fourth = key % LETTER_COUNT;
        key = (key - fourth) / LETTER_COUNT;
        final int third = key % LETTER_COUNT;
        key = (key - third) / LETTER_COUNT;
        final int second = key % LETTER_COUNT;
        key = (key - second) / LETTER_COUNT;
        final int first = key % LETTER_COUNT;
        key = (key - first) / LETTER_COUNT;
        assertEquals(0, key, "number chosen was greater than 26^5 - 1; make sure maximumKey <= " + ZZZZZ);
        return "" + LETTERS.charAt(first) + LETTERS.charAt(second) + LETTERS.charAt(third) + LETTERS.charAt(fourth)
                + LETTERS.charAt(fifth);
    }

    public long wordToKey(final String word) {
        long value = 0;
        for (int position = 0; position < word.length(); position++) {
            value *= LETTER_COUNT;
            value += (word.charAt(position) - 'a');
        }
        return value;
    }

    /**
     * @param description
     * 		a description of the parameters, used to name test run
     * @param initialMapSize
     * 		how many key/values to store in both teacherMap and learnerMap before the reconnect
     * @param maximumKey
     * 		how many distinct Key values are allowed to exist
     * @param operations
     * 		how many create/update/delete operations to perform on just the teacherMap, prior to the
     * 		reconnect
     * @param operationsPerCopy
     * 		how often (in terms of operations) to create a new copy of the teacherMap, prior to
     * 		reconnect
     * @param maxCopiesInMemory
     * 		how many copies are allowed to exist before we manually start to release them (oldest
     * 		first)
     * @param createWeight
     * 		relative weight of create operations compared to updates and deletes
     * @param updateWeight
     * 		relative weight of update operations compared to creates and deletes
     * @param deleteWeight
     * 		relative weight of delete operations compared to creates and updates
     */
    private record RandomOperationsConfig(
            String description,
            int initialMapSize,
            int maximumKey,
            int operations,
            int operationsPerCopy,
            int maxCopiesInMemory,
            int createWeight,
            int updateWeight,
            int deleteWeight) {

        @Override
        public String toString() {
            return description;
        }
    }

    private static Stream<Arguments> buildArguments() {
        final List<Arguments> arguments = new ArrayList<>();

        arguments.add(Arguments.of(
                new RandomOperationsConfig("Small tree, random operations", 100, 200, 100, 10, 3, 1, 1, 1)));

        arguments.add(Arguments.of(
                new RandomOperationsConfig("Medium tree, random operations", 1_000, 2_000, 1_000, 100, 3, 1, 1, 1)));

        arguments.add(Arguments.of(
                new RandomOperationsConfig("Medium tree, many insertions", 1_000, 2_000, 1_000, 100, 3, 2, 1, 1)));

        arguments.add(Arguments.of(
                new RandomOperationsConfig("Medium tree, many updates", 1_000, 2_000, 1_000, 100, 3, 1, 2, 1)));

        arguments.add(Arguments.of(new RandomOperationsConfig(
                "Large tree, random operations", 10_000, 20_000, 10_000, 1_000, 3, 1, 1, 1)));

        arguments.add(Arguments.of(
                new RandomOperationsConfig("Large tree, many deletions", 10_000, 20_000, 10_000, 1_000, 3, 1, 1, 2)));

        arguments.add(Arguments.of(new RandomOperationsConfig(
                "Large tree, mostly just deletions", 10_000, 20_000, 10_000, 1_000, 3, 1, 1, 10)));

        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Random Operations Reconnect Test")
    void randomOperationsReconnectTest(final RandomOperationsConfig config) throws Exception {
        final Random random = getRandomPrintSeed();

        // validation of input variables
        assertTrue(config.initialMapSize >= 0, "initialMapSize must be non-negative.");
        assertTrue(config.maximumKey >= 0, "maximumKey must be non-negative.");
        assertTrue(config.maximumKey <= ZZZZZ, "maximumKey must be no larger than 26^5 (" + ZZZZZ + ").");
        assertTrue(config.operations >= 0, "operations must be non-negative.");
        assertTrue(config.operationsPerCopy >= 0, "operationsPerCopy must be non-negative.");
        assertTrue(config.maxCopiesInMemory >= 0, "maxCopiesInMemory must be non-negative.");
        assertTrue(config.createWeight >= 0, "createWeight must be non-negative.");
        assertTrue(config.updateWeight >= 0, "updateWeight must be non-negative.");
        assertTrue(config.deleteWeight >= 0, "deleteWeight must be non-negative.");

        // keys, which are random 5-letter "words", are stored as Strings in the following two sets, even though they
        // are used as long-valued TestKeys.  The usedKeys set contains all the strings currently used as TestKeys;
        // removedKeys is used at the end of the test, to ensure that the learnerTree does not contain
        // any of those keys.
        final RandomAccessSet<String> usedKeys = new RandomAccessHashSet<>();
        final Set<String> removedKeys = new HashSet<>();

        // create initial maps with initialMapSize
        while (teacherMap.size() < config.initialMapSize()) {
            final String key = randomWord(random, config.maximumKey());
            final String value = randomWord(random, ZZZZZ);
            // treat the hashCode of the key as a long value for the TestKey
            teacherMap.put(new TestKey(wordToKey(key)), new TestValue(value));
            learnerMap.put(new TestKey(wordToKey(key)), new TestValue(value));
            usedKeys.add(key);
        }
        final Queue<VirtualMap<TestKey, TestValue>> copiesQueue = new LinkedList<>();

        for (int operation = 0; operation < config.operations(); operation++) {
            int op = random.nextInt(config.createWeight() + config.updateWeight() + config.deleteWeight());
            if (op < config.createWeight()) {
                // add a new key to teacherMap
                String key = randomWord(random, config.maximumKey());
                while (usedKeys.contains(key)) {
                    key = randomWord(random, config.maximumKey());
                }
                final String value = randomWord(random, ZZZZZ);
                teacherMap.put(new TestKey(wordToKey(key)), new TestValue(value));
                usedKeys.add(key);
                removedKeys.remove(key);
            } else if (op < config.createWeight() + config.updateWeight()) {
                // update an existing key from the teacherMap
                final String key = usedKeys.get(random);
                final String value = randomWord(random, ZZZZZ);
                teacherMap.put(new TestKey(wordToKey(key)), new TestValue(value));
            } else {
                // remove an existing key from the teacherMap
                final String key = usedKeys.get(random);
                teacherMap.remove(new TestKey(wordToKey(key)));
                usedKeys.remove(key);
                removedKeys.add(key);
            }

            if (operation > 0 && operation % config.operationsPerCopy() == 0) {
                copiesQueue.add(teacherMap);
                teacherMap = teacherMap.copy();
                if (copiesQueue.size() > config.maxCopiesInMemory()) {
                    final VirtualMap<TestKey, TestValue> oldestCopy = copiesQueue.remove();
                    oldestCopy.release();
                }
            }
        }

        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final VirtualMap<TestKey, TestValue> copy = teacherMap.copy(); // ensure teacherMap is immutable
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);

        // reconnect happening
        final DummyMerkleInternal afterSyncLearnerTree =
                MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree, reconnectConfig);

        final DummyMerkleInternal node = afterSyncLearnerTree.getChild(1);
        final VirtualMap<TestKey, TestValue> afterMap = node.getChild(3);

        for (final String key : removedKeys) {
            try {
                assertNull(
                        afterMap.get(new TestKey(wordToKey(key))),
                        "Key " + key + " should no longer be present after reconnect.");
            } catch (AssertionError ae) {
                assertEquals(
                        "Found an illegal path in keyToPathMap!",
                        ae.getMessage()); // ignore just that error for this loop
            }
        }

        // release all queued copies
        while (copiesQueue.size() > 0) {
            copiesQueue.remove().release();
        }

        afterSyncLearnerTree.release();
        copy.release();
        teacherTree.release();
        learnerTree.release();
    }

    /**
     * 	#5926: Virtual map hashing goes into infinite loop after reconnect
     */
    @Test
    @DisplayName("#5926 regression test")
    void regression05926() throws Exception {
        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final VirtualMap<TestKey, TestValue> copy = teacherMap.copy();
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);

        final DummyMerkleInternal afterSyncLearnerTree =
                MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree, reconnectConfig);

        final DummyMerkleInternal node = afterSyncLearnerTree.getChild(1);
        final VirtualMap<TestKey, TestValue> afterMap = node.getChild(3);

        // Create a copy of the resulting map
        final VirtualMap<TestKey, TestValue> afterCopy = afterMap.copy();
        // Enforce computing the hash of its root node
        assertNotNull(afterCopy.getRight().getHash());

        afterSyncLearnerTree.release();
        copy.release();
        afterCopy.release();
        teacherTree.release();
        learnerTree.release();
    }

    @Test
    void metricsAfterReconnect() throws Exception {
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
        learnerMap.registerMetrics(metrics);

        Metric sizeMetric = metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, "vmap_size_Learner");
        assertNotNull(sizeMetric);
        assertEquals(0L, sizeMetric.get(ValueType.VALUE));

        final TestKey zeroKey = new TestKey(0);
        teacherMap.put(zeroKey, new TestValue("value0"));
        learnerMap.put(zeroKey, new TestValue("value0"));
        assertEquals(1L, sizeMetric.get(ValueType.VALUE));

        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final TestKey key = new TestKey(123);
        teacherMap.put(key, new TestValue("value123"));

        final VirtualMap<TestKey, TestValue> teacherCopy = teacherMap.copy();
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);

        final DummyMerkleInternal afterSyncLearnerTree =
                MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree, reconnectConfig);

        final DummyMerkleInternal node = afterSyncLearnerTree.getChild(1);
        final VirtualMap<TestKey, TestValue> afterLearnerMap = node.getChild(3);
        final VirtualMap<TestKey, TestValue> afterCopy = afterLearnerMap.copy();

        assertTrue(afterCopy.containsKey(key));
        assertEquals("value123", afterCopy.get(key).getValue());
        assertEquals(2L, sizeMetric.get(ValueType.VALUE));

        final TestKey key2 = new TestKey(456);
        afterCopy.put(key2, new TestValue("value456"));
        assertEquals(3L, sizeMetric.get(ValueType.VALUE));

        teacherCopy.release();
        afterCopy.release();
        teacherTree.release();
        learnerTree.release();
    }
}
