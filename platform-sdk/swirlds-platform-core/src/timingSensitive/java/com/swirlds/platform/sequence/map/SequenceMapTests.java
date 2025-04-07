// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sequence.map;

import static com.swirlds.common.test.fixtures.AssertionUtils.completeBeforeTimeout;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Sequence Map Tests")
public class SequenceMapTests {

    private record SequenceMapKey(int key, long sequence) {
        @Override
        public int hashCode() {
            return key;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj instanceof final SequenceMapKey that) {
                return this.key == that.key;
            }
            return false;
        }

        @Override
        public String toString() {
            return key + "[" + sequence + "]";
        }
    }

    @FunctionalInterface
    private interface MapConstructor {
        SequenceMap<SequenceMapKey, Integer> newMap(
                final long initialFirstSequenceNumber, final int sequenceNumberCapacity, final boolean allowExpansion);
    }

    private record MapBuilder(String name, MapConstructor constructor) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> testConfiguration() {
        return Stream.of(
                Arguments.of(new MapBuilder(
                        "standard",
                        (min, capacity, allowExpansion) ->
                                new StandardSequenceMap<>(min, capacity, allowExpansion, SequenceMapKey::sequence))),
                Arguments.of(new MapBuilder(
                        "concurrent",
                        (min, capacity, allowExpansion) ->
                                new ConcurrentSequenceMap<>(min, capacity, allowExpansion, SequenceMapKey::sequence))));
    }

    private static boolean isKeyPresent(final SequenceMap<SequenceMapKey, Integer> map, final Long sequenceNumber) {
        return sequenceNumber != null
                && sequenceNumber >= map.getFirstSequenceNumberInWindow()
                && sequenceNumber <= map.getLastSequenceNumberInWindow();
    }

    /**
     * Do validation on a map.
     *
     * @param map                  the map being validated
     * @param smallestKeyToCheck   the smallest key to check
     * @param keyToCheckUpperBound the upper bound (exclusive) of keys to check
     * @param getSequenceNumber    provides the expected sequence number for a key, or null if the key is not expected
     *                             to be in the map
     * @param getValue             provides the expected value for a key (result is ignored if sequence number is
     *                             reported as null or the sequence number falls outside the map's bounds)
     */
    private void validateMapContents(
            final SequenceMap<SequenceMapKey, Integer> map,
            final int smallestKeyToCheck,
            final int keyToCheckUpperBound,
            final Function<Integer, Long> getSequenceNumber,
            final Function<Integer, Integer> getValue) {

        final Map<Long, Set<Integer>> keysBySequenceNumber = new HashMap<>();
        long smallestSequenceNumber = Long.MAX_VALUE;
        long largestSequenceNumber = Long.MIN_VALUE;
        int size = 0;

        // Query by key
        for (int key = smallestKeyToCheck; key < keyToCheckUpperBound; key++) {
            final Long sequenceNumber = getSequenceNumber.apply(key);

            if (isKeyPresent(map, sequenceNumber)) {
                assertEquals(getValue.apply(key), map.get(new SequenceMapKey(key, sequenceNumber)), "unexpected value");

                assertTrue(
                        map.containsKey(new SequenceMapKey(key, getSequenceNumber.apply(key))), "should contain key");

                keysBySequenceNumber
                        .computeIfAbsent(sequenceNumber, k -> new HashSet<>())
                        .add(key);
                smallestSequenceNumber = Math.min(smallestSequenceNumber, sequenceNumber);
                largestSequenceNumber = Math.max(largestSequenceNumber, sequenceNumber);

                size++;

            } else {
                // Note: the sequence number in the key is unused when we are just querying. So it's
                // ok to lie and provide a sequence number of 0 here, even though 0 may not be the
                // correct sequence number for the given key.
                assertNull(map.get(new SequenceMapKey(key, 0)), "unexpected value for key " + key);
                assertFalse(map.containsKey(new SequenceMapKey(key, 0)), "should not contain key");
            }
        }

        assertEquals(size, map.getSize(), "unexpected map size");
        if (size == 0) {
            // For the sake of sanity, we don't want to attempt to use the default values for these
            // variables under any conditions.
            smallestSequenceNumber = map.getFirstSequenceNumberInWindow();
            largestSequenceNumber = map.getLastSequenceNumberInWindow();
        }

        // Query by sequence number
        // Start at 100 sequence numbers below the minimum, and query to 100 sequence numbers beyond the maximum
        for (long sequenceNumber = smallestSequenceNumber - 100;
                sequenceNumber < largestSequenceNumber + 100;
                sequenceNumber++) {

            final Set<Integer> expectedKeys = keysBySequenceNumber.get(sequenceNumber);
            if (expectedKeys == null) {
                assertTrue(map.getKeysWithSequenceNumber(sequenceNumber).isEmpty(), "map should not contain any keys");
                assertTrue(
                        map.getEntriesWithSequenceNumber(sequenceNumber).isEmpty(),
                        "map should not contain any entries");
            } else {
                final List<SequenceMapKey> keys = map.getKeysWithSequenceNumber(sequenceNumber);
                assertEquals(expectedKeys.size(), keys.size(), "unexpected number of keys returned");
                for (final SequenceMapKey key : keys) {
                    assertTrue(expectedKeys.contains(key.key), "key not in expected set");
                    assertEquals(getSequenceNumber.apply(key.key), key.sequence, "unexpected sequence number");
                }

                final List<Map.Entry<SequenceMapKey, Integer>> entries =
                        map.getEntriesWithSequenceNumber(sequenceNumber);
                assertEquals(expectedKeys.size(), entries.size(), "unexpected number of entries returned");
                for (final Map.Entry<SequenceMapKey, Integer> entry : entries) {
                    assertTrue(expectedKeys.contains(entry.getKey().key), "key not in expected set");
                    assertEquals(
                            getSequenceNumber.apply(entry.getKey().key),
                            entry.getKey().sequence,
                            "unexpected sequence number");
                    assertEquals(getValue.apply(entry.getKey().key), entry.getValue(), "unexpected value");
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Simple Access Test")
    void simpleAccessTest(final MapBuilder mapBuilder) {
        // The number of things inserted into the map
        final int size = 100;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, size, false);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Positive Start Access Test")
    void positiveStartAccessTest(final MapBuilder mapBuilder) {
        final int size = 100;
        final int start = 50;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(start / keysPerSeq, size, false);

        for (int offset = 0; offset < size; offset++) {

            final int key = start + offset;

            map.put(new SequenceMapKey(key, key / keysPerSeq), -key);
            assertEquals(offset + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= start && key < start + size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Negative Start Access Test")
    void negativeStartAccessTest(final MapBuilder mapBuilder) {
        // The number of things inserted into the map
        final int size = 100;
        final int start = -50;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(start / keysPerSeq, size, false);

        for (int offset = 0; offset < size; offset++) {

            final int key = start + offset;

            map.put(new SequenceMapKey(key, key / keysPerSeq), -key);
            assertEquals(offset + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= start && key < start + size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Single Shift Test")
    void singleShiftTest(final MapBuilder mapBuilder) {
        // The number of things inserted into the map
        final int size = 100;
        // The number of keys for each sequence number
        final int keysPerSeq = 5;
        final int capacity = size / keysPerSeq;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, capacity, false);
        assertEquals(0, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        map.shiftWindow(size / 2 / keysPerSeq);

        assertEquals(size / 2 / keysPerSeq, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(
                size / 2 / keysPerSeq + capacity - 1, map.getLastSequenceNumberInWindow(), "unexpected upper bound");

        assertEquals(size / 2, map.getSize(), "unexpected size");

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Single Shift Test")
    void bigShiftTest(final MapBuilder mapBuilder) {
        // The number of things inserted into the map
        final int size = 100_000;
        // The number of keys for each sequence number
        final int keysPerSeq = 5;
        final int capacity = size / keysPerSeq;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, capacity, false);
        assertEquals(0, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        map.shiftWindow(size / 2 / keysPerSeq);

        assertEquals(size / 2 / keysPerSeq, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(
                size / 2 / keysPerSeq + capacity - 1, map.getLastSequenceNumberInWindow(), "unexpected upper bound");

        assertEquals(size / 2, map.getSize(), "unexpected size");

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("purge() With Callback Test")
    void purgeWithCallbackTest(final MapBuilder mapBuilder) {

        // The number of things inserted into the map
        final int size = 100;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, size, false);
        assertEquals(0, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        final Set<Integer> purgedKeys = new HashSet<>();
        map.shiftWindow(size / 2 / keysPerSeq, (key, value) -> {
            assertTrue(key.sequence < size / 2 / keysPerSeq, "key should not be purged");
            assertEquals(key.key / keysPerSeq, key.sequence, "unexpected sequence number for key");
            assertEquals(value, -key.key, "unexpected value");
            assertTrue(purgedKeys.add(key.key), "callback should be invoked once per key");
        });

        assertEquals(size / 2, purgedKeys.size(), "unexpected number of keys purged");

        assertEquals(size / 2 / keysPerSeq, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(size / 2, map.getSize(), "unexpected size");

        validateMapContents(
                map,
                0,
                2 * size,
                key -> {
                    if (key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Upper/Lower Bound Test")
    void upperLowerBoundTest(final MapBuilder mapBuilder) {
        // The number of things inserted into the map
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(5, 5, false);

        assertEquals(5, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(9, map.getLastSequenceNumberInWindow(), "unexpected upper bound");

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
        }

        validateMapContents(map, 0, 2 * size, key -> (long) key / keysPerSeq, key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Shifting Window Test")
    void shiftingWindowTest(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        final int size = 100;
        final int capacity = 5;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        int initialLowerBound = -10;
        int lowerBound = initialLowerBound;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        for (int iteration = 0; iteration < 10; iteration++) {
            // shift the lower bound
            lowerBound += random.nextInt(0, 20);
            map.shiftWindow(lowerBound);

            assertEquals(lowerBound, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");
            assertEquals(
                    capacity,
                    map.getLastSequenceNumberInWindow() - map.getFirstSequenceNumberInWindow() + 1,
                    "unexpected capacity");

            // Add a bunch of values. Values outside the window should be ignored.
            for (int i = lowerBound * keysPerSeq - 100;
                    i < map.getLastSequenceNumberInWindow() * keysPerSeq + 100;
                    i++) {

                map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            }

            validateMapContents(
                    map,
                    -size,
                    (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + size),
                    key -> {
                        if (key / keysPerSeq >= initialLowerBound) {
                            return (long) key / keysPerSeq;
                        } else {
                            // key is not present
                            return null;
                        }
                    },
                    key -> -key);
        }
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("One Directional Shift Test")
    void oneDirectionalShiftTest(final MapBuilder mapBuilder) {
        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(-10, 10, false);

        // Shifting in the positive direction should not cause problems
        map.shiftWindow(-5);
        map.shiftWindow(0);
        map.shiftWindow(5);
        map.shiftWindow(10);
        map.shiftWindow(15);
        map.shiftWindow(20);

        // Shifting by 0 is legal
        map.shiftWindow(20);

        // Shifting in the negative direction is not permitted
        assertThrows(
                IllegalStateException.class,
                () -> map.shiftWindow(15),
                "should not be able to shift in the negative direction");
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("clear() Test")
    void clearTest(final MapBuilder mapBuilder) {

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 50;
        final int capacity = 5;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        assertEquals(lowerBound, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < map.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            map.put(new SequenceMapKey(i, i / 5), -i);
        }

        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> (long) key / keysPerSeq,
                key -> -key);

        // Shift the window.
        final int newLowerBound = lowerBound + 10;
        map.shiftWindow(newLowerBound);

        assertEquals(newLowerBound, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < map.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            map.put(new SequenceMapKey(i, i / 5), -i);
        }

        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> (long) key / keysPerSeq,
                key -> -key);

        map.clear();

        // should revert to original bounds
        assertEquals(lowerBound, map.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        assertEquals(0, map.getSize(), "map should be empty");

        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> null, // no values permitted
                key -> -key);

        // Reinserting values should work the same way as when the map was "fresh"
        for (int i = 0; i < map.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            map.put(new SequenceMapKey(i, i / 5), -i);
        }

        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> (long) key / keysPerSeq,
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("remove() Test")
    void removeTest(final MapBuilder mapBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;
        final int capacity = 20;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        // removing values from an empty map shouldn't cause problems
        assertNull(map.remove(new SequenceMapKey(-100, 0)), "value should not be in map");
        assertNull(map.remove(new SequenceMapKey(0, 0)), "value should not be in map");
        assertNull(map.remove(new SequenceMapKey(50, 0)), "value should not be in map");
        assertNull(map.remove(new SequenceMapKey(100, 0)), "value should not be in map");

        // Validate removal of an existing value
        assertEquals(0, map.getSize(), "map should be empty");
        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> null, // no values should be present
                key -> -key);

        map.put(new SequenceMapKey(10, 2), -10);
        assertEquals(-10, map.remove(new SequenceMapKey(10, 2)), "unexpected value");

        assertEquals(0, map.getSize(), "map should be empty");
        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> null, // no values should be present
                key -> -key);

        // Re-inserting after removal should work the same as regular insertion
        map.put(new SequenceMapKey(10, 2), -10);
        assertEquals(-10, map.get(new SequenceMapKey(10, 2)), "unexpected value");

        assertEquals(1, map.getSize(), "map should contain one thing");
        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Replacing put() Test")
    void replacingPutTest(final MapBuilder mapBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;
        final int capacity = 20;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        assertNull(map.put(new SequenceMapKey(10, 2), -10), "no value should currently be in map");

        assertEquals(1, map.getSize(), "map should contain one thing");
        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);

        assertEquals(-10, map.put(new SequenceMapKey(10, 2), 1234), "previous value should be returned");

        assertEquals(1, map.getSize(), "map should contain one thing");
        validateMapContents(
                map,
                0,
                (int) (map.getLastSequenceNumberInWindow() * keysPerSeq + 100),
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> 1234);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("computeIfAbsent() Test")
    void computeIfAbsentTest(final MapBuilder mapBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;
        final int capacity = 20;

        // The highest permitted sequence number
        final int upperBound = 100;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        // Value that is in a legal range and not present
        assertEquals(-10, map.computeIfAbsent(new SequenceMapKey(10, 2), key -> -key.key), "incorrect value returned");

        assertEquals(1, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);

        // Values that are in an illegal range
        assertNull(map.computeIfAbsent(new SequenceMapKey(-1000, -1000), key -> -key.key), "incorrect value returned");
        assertNull(map.computeIfAbsent(new SequenceMapKey(1000, 1000), key -> -key.key), "incorrect value returned");

        assertEquals(1, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);

        // Value that is already present
        map.put(new SequenceMapKey(20, 4), -20);
        assertEquals(
                -20,
                map.computeIfAbsent(new SequenceMapKey(20, 2), key -> {
                    fail("should never be called");
                    return null;
                }),
                "incorrect value returned");

        assertEquals(2, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else if (key == 20) {
                        return 4L;
                    } else {
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("putIfAbsent() Test")
    void putIfAbsentTest(final MapBuilder mapBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;
        final int capacity = 20;

        // The highest permitted sequence number
        final int upperBound = 100;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(lowerBound, capacity, false);

        // Value that is in a legal range and not present
        assertTrue(map.putIfAbsent(new SequenceMapKey(10, 2), -10), "value is not yet in the map");

        assertEquals(1, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);

        // Values that are in an illegal range
        assertFalse(map.putIfAbsent(new SequenceMapKey(-1000, -1000), 1000), "incorrect value returned");
        assertFalse(map.putIfAbsent(new SequenceMapKey(1000, 1000), -1000), "incorrect value returned");

        assertEquals(1, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else {
                        return null;
                    }
                },
                key -> -key);

        // Value that is already present
        map.put(new SequenceMapKey(20, 4), -20);
        assertFalse(map.putIfAbsent(new SequenceMapKey(20, 2), 1234), "incorrect value returned");

        assertEquals(2, map.getSize(), "unexpected size");
        validateMapContents(
                map,
                0,
                upperBound * keysPerSeq + 100,
                key -> {
                    if (key == 10) {
                        return 2L;
                    } else if (key == 20) {
                        return 4L;
                    } else {
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("removeSequenceNumber() Test")
    void removeSequenceNumberTest(final MapBuilder mapBuilder) {
        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, 100, false);

        // The number of things inserted into the map
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / 5), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Remove sequence numbers that are not in the map
        map.removeValuesWithSequenceNumber(-1000);
        map.removeValuesWithSequenceNumber(1000);
        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Remove a sequence number that is in the map
        map.removeValuesWithSequenceNumber(1);

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        final long sequenceNumber = key / keysPerSeq;
                        if (sequenceNumber == 1) {
                            return null;
                        }
                        return sequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Removing the same sequence number a second time shouldn't have any ill effects
        map.removeValuesWithSequenceNumber(1);

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        final long sequenceNumber = key / keysPerSeq;
                        if (sequenceNumber == 1) {
                            return null;
                        }
                        return sequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // It should be ok to re-insert into the removed sequence number
        map.put(new SequenceMapKey(5, 1), 5);
        map.put(new SequenceMapKey(6, 1), 6);
        map.put(new SequenceMapKey(7, 1), 7);
        map.put(new SequenceMapKey(8, 1), 8);
        map.put(new SequenceMapKey(9, 1), 9);

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> {
                    final long sequenceNumber = key / keysPerSeq;
                    if (sequenceNumber == 1) {
                        return key;
                    }
                    return -key;
                });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("removeSequenceNumber() With Callback Test")
    void removeSequenceNumberWithCallbackTest(final MapBuilder mapBuilder) {
        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, 100, false);

        // The number of things inserted into the map
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            map.put(new SequenceMapKey(i, i / 5), -i);
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Remove sequence numbers that are not in the map
        map.removeValuesWithSequenceNumber(-1000, (key, value) -> fail("should not be called"));
        map.removeValuesWithSequenceNumber(1000, (key, value) -> fail("should not be called"));
        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Remove a sequence number that is in the map
        final Set<Integer> removedKeys = new HashSet<>();
        map.removeValuesWithSequenceNumber(1, (key, value) -> {
            assertEquals(1, key.sequence, "key should not be removed");
            assertEquals(key.key / keysPerSeq, key.sequence, "unexpected sequence number for key");
            assertEquals(value, -key.key, "unexpected value");
            assertTrue(removedKeys.add(key.key), "callback should be invoked once per key");
        });
        assertEquals(5, removedKeys.size(), "unexpected number of keys removed");

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        final long sequenceNumber = key / keysPerSeq;
                        if (sequenceNumber == 1) {
                            return null;
                        }
                        return sequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // Removing the same sequence number a second time shouldn't have any ill effects
        map.removeValuesWithSequenceNumber(1, (key, value) -> fail("should not be called"));

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        final long sequenceNumber = key / keysPerSeq;
                        if (sequenceNumber == 1) {
                            return null;
                        }
                        return sequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);

        // It should be ok to re-insert into the removed sequence number
        map.put(new SequenceMapKey(5, 1), 5);
        map.put(new SequenceMapKey(6, 1), 6);
        map.put(new SequenceMapKey(7, 1), 7);
        map.put(new SequenceMapKey(8, 1), 8);
        map.put(new SequenceMapKey(9, 1), 9);

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> {
                    final long sequenceNumber = key / keysPerSeq;
                    if (sequenceNumber == 1) {
                        return key;
                    }
                    return -key;
                });
    }

    @Test
    @DisplayName("Parallel SequenceMap Test")
    void parallelSequenceMapTest() throws InterruptedException {
        final Random random = new Random();

        final AtomicInteger lowerBound = new AtomicInteger(0);
        final int capacity = 5;

        final SequenceMap<SequenceMapKey, Integer> map =
                new ConcurrentSequenceMap<>(lowerBound.get(), capacity, SequenceMapKey::sequence);

        final AtomicBoolean error = new AtomicBoolean();

        final StoppableThread purgeThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setMinimumPeriod(Duration.ofMillis(10))
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    error.set(true);
                })
                .setWork(() -> {

                    // Verify that no data is present that should not be present
                    for (int sequenceNumber = lowerBound.get() - 100;
                            sequenceNumber < map.getLastSequenceNumberInWindow() + 100;
                            sequenceNumber++) {

                        if (sequenceNumber < lowerBound.get() || sequenceNumber > map.getLastSequenceNumberInWindow()) {
                            final List<SequenceMapKey> keys = map.getKeysWithSequenceNumber(sequenceNumber);
                            assertEquals(0, keys.size(), "no keys should be present for this round");
                        }
                    }

                    // shift the window
                    lowerBound.getAndAdd(5);
                    map.shiftWindow(lowerBound.get());
                })
                .build(true);

        final int threadCount = 4;
        final List<StoppableThread> updaterThreads = new LinkedList<>();
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            updaterThreads.add(new StoppableThreadConfiguration<>(getStaticThreadManager())
                    .setExceptionHandler((t, e) -> {
                        e.printStackTrace();
                        error.set(true);
                    })
                    .setWork(() -> {
                        final double choice = random.nextDouble();
                        final int sequenceNumber =
                                random.nextInt(lowerBound.get() - 50, (int) (map.getLastSequenceNumberInWindow() + 50));

                        if (choice < 0.5) {
                            // attempt to delete a value
                            final int key = random.nextInt(sequenceNumber * 10, sequenceNumber * 10 + 10);
                            map.remove(new SequenceMapKey(key, sequenceNumber));

                        } else if (choice < 0.999) {
                            // insert/replace a value
                            final int key = random.nextInt(sequenceNumber * 10, sequenceNumber * 10 + 10);
                            final int value = random.nextInt();
                            map.put(new SequenceMapKey(key, sequenceNumber), value);

                        } else {
                            // very rarely, delete an entire sequence number
                            map.removeValuesWithSequenceNumber(sequenceNumber);
                        }
                    })
                    .build(true));
        }

        // Let the threads fight each other for a little while. At the end, tear everything down and make sure
        // our constraints weren't violated.
        SECONDS.sleep(2);
        purgeThread.stop();
        updaterThreads.forEach(Stoppable::stop);

        completeBeforeTimeout(() -> purgeThread.join(), Duration.ofSeconds(1), "thread did not die on time");
        updaterThreads.forEach(thread -> {
            try {
                completeBeforeTimeout(() -> thread.join(), Duration.ofSeconds(1), "thread did not die on time");
            } catch (InterruptedException e) {
                fail(e);
            }
        });

        assertFalse(error.get(), "error(s) encountered");
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Expand Start From Sequence 0 Test")
    void expandStartFromSequence0Test(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        // The number of things inserted into the map
        final int size = 10_000;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, 1, true);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            } else {
                map.putIfAbsent(new SequenceMapKey(i, i / keysPerSeq), -i);
            }
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Expand Start From Negative Sequence Test")
    void expandStartFromNegativeSequenceTest(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        // The number of things inserted into the map
        final int size = 10_000;

        final int initialSequenceNumber = -42;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(initialSequenceNumber, 1, true);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(i, i / keysPerSeq + initialSequenceNumber), -i);
            } else {
                map.putIfAbsent(new SequenceMapKey(i, i / keysPerSeq + initialSequenceNumber), -i);
            }
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq + initialSequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Expand Start From Positive Sequence Test")
    void expandStartFromPositiveSequenceTest(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        // The number of things inserted into the map
        final int size = 10_000;

        final int initialSequenceNumber = 42;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(initialSequenceNumber, 1, true);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(i, i / keysPerSeq + initialSequenceNumber), -i);
            } else {
                map.putIfAbsent(new SequenceMapKey(i, i / keysPerSeq + initialSequenceNumber), -i);
            }
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq + initialSequenceNumber;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Expand And Shift Test")
    void expandAndShiftTest(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        final int phaseOneCount = 1_000;
        final int phaseTwoCount = 10_000;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, 1, true);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < phaseOneCount; i++) {
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            } else {
                map.putIfAbsent(new SequenceMapKey(i, i / keysPerSeq), -i);
            }
            assertEquals(i + 1, map.getSize(), "unexpected size");
        }

        // This shift will cause us to remove half of the elements added during phase 1
        final long firstSeqAfterShift = phaseOneCount / keysPerSeq / 2;
        map.shiftWindow(firstSeqAfterShift);
        final int countAfterPhase1 = map.getSize();

        for (int i = 0; i < phaseTwoCount; i++) {
            final int key = i + phaseOneCount;
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(key, key / keysPerSeq), -key);
            } else {
                map.putIfAbsent(new SequenceMapKey(key, key / keysPerSeq), -key);
            }
            assertEquals(countAfterPhase1 + i + 1, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -phaseOneCount,
                2 * phaseTwoCount,
                key -> {
                    if (key < (phaseTwoCount + phaseOneCount) && (key / keysPerSeq) >= firstSeqAfterShift) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Sudden Expansion Test")
    void suddenExpansionTest(final MapBuilder mapBuilder) {
        final Random random = getRandomPrintSeed();

        // The number of things inserted into the map
        final int size = 10_000;

        final SequenceMap<SequenceMapKey, Integer> map = mapBuilder.constructor.newMap(0, 1, true);

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // Intentionally start inserting high sequence numbers first
        for (int i = size - 1; i >= 0; i--) {
            if (random.nextBoolean()) {
                map.put(new SequenceMapKey(i, i / keysPerSeq), -i);
            } else {
                map.putIfAbsent(new SequenceMapKey(i, i / keysPerSeq), -i);
            }
            assertEquals(size - i, map.getSize(), "unexpected size");
        }

        validateMapContents(
                map,
                -size,
                2 * size,
                key -> {
                    if (key >= 0 && key < size) {
                        return (long) key / keysPerSeq;
                    } else {
                        // key is not present
                        return null;
                    }
                },
                key -> -key);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Expansion Limits Test")
    void expansionLimitsTest(final MapBuilder mapBuilder) {
        final SequenceMap<SequenceMapKey, Integer> map1 = mapBuilder.constructor.newMap(0, 1, true);
        assertThrows(IllegalStateException.class, () -> map1.put(new SequenceMapKey(1, Integer.MAX_VALUE - 7), 1));
        assertThrows(IllegalStateException.class, () -> map1.put(new SequenceMapKey(1, Integer.MAX_VALUE), 1));
        assertThrows(IllegalStateException.class, () -> map1.put(new SequenceMapKey(1, Long.MAX_VALUE), 1));

        final SequenceMap<SequenceMapKey, Integer> map2 = mapBuilder.constructor.newMap(Long.MIN_VALUE, 1, true);
        assertThrows(IllegalStateException.class, () -> map2.put(new SequenceMapKey(1, Integer.MAX_VALUE - 7), 1));
        assertThrows(IllegalStateException.class, () -> map2.put(new SequenceMapKey(1, Integer.MAX_VALUE), 1));
        assertThrows(IllegalStateException.class, () -> map2.put(new SequenceMapKey(1, Long.MAX_VALUE), 1));
    }
}
