// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.set;

import static com.swirlds.common.test.fixtures.AssertionUtils.completeBeforeTimeout;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("SequenceSet Tests")
public class SequenceSetTests {

    private record SequenceSetElement(int key, long sequence) {
        @Override
        public int hashCode() {
            return key;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj instanceof final SequenceSetElement that) {
                return this.key == that.key;
            }
            return false;
        }

        @Override
        public String toString() {
            return key + "[" + sequence + "]";
        }
    }

    private record SetBuilder(String name, BiFunction<Long, Integer, SequenceSet<SequenceSetElement>> constructor) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> testConfiguration() {
        return Stream.of(
                Arguments.of(new SetBuilder(
                        "standard",
                        (min, capacity) -> new StandardSequenceSet<>(min, capacity, SequenceSetElement::sequence))),
                Arguments.of(new SetBuilder(
                        "concurrent",
                        (min, capacity) -> new ConcurrentSequenceSet<>(min, capacity, SequenceSetElement::sequence))));
    }

    private static boolean isKeyPresent(final SequenceSet<SequenceSetElement> set, final Long sequenceNumber) {
        return sequenceNumber != null
                && sequenceNumber >= set.getFirstSequenceNumberInWindow()
                && sequenceNumber <= set.getLastSequenceNumberInWindow();
    }

    /**
     * Do validation on a set.
     *
     * @param set
     * 		the set being validated
     * @param smallestKeyToCheck
     * 		the smallest key to check
     * @param keyToCheckUpperBound
     * 		the upper bound (exclusive) of keys to check
     * @param getSequenceNumber
     * 		provides the expected sequence number for a key, or null if the key is not expected to be in the set
     */
    private void validateSetContents(
            final SequenceSet<SequenceSetElement> set,
            final int smallestKeyToCheck,
            final int keyToCheckUpperBound,
            final Function<Integer, Long> getSequenceNumber) {

        final Map<Long, Set<Integer>> keysBySequenceNumber = new HashMap<>();
        long smallestSequenceNumber = Long.MAX_VALUE;
        long largestSequenceNumber = Long.MIN_VALUE;
        int size = 0;

        // Query by key
        for (int key = smallestKeyToCheck; key < keyToCheckUpperBound; key++) {
            final Long sequenceNumber = getSequenceNumber.apply(key);

            if (isKeyPresent(set, sequenceNumber)) {

                assertTrue(
                        set.contains(new SequenceSetElement(key, getSequenceNumber.apply(key))), "should contain key");

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
                assertFalse(set.contains(new SequenceSetElement(key, 0)), "should not contain key");
            }
        }

        assertEquals(size, set.getSize(), "unexpected set size");
        if (size == 0) {
            // For the sake of sanity, we don't want to attempt to use the default values for these
            // variables under any conditions.
            smallestSequenceNumber = set.getFirstSequenceNumberInWindow();
            largestSequenceNumber = set.getLastSequenceNumberInWindow();
        }

        // Query by sequence number
        // Start at 100 sequence numbers below the minimum, and query to 100 sequence numbers beyond the maximum
        for (long sequenceNumber = smallestSequenceNumber - 100;
                sequenceNumber < largestSequenceNumber + 100;
                sequenceNumber++) {

            final Set<Integer> expectedKeys = keysBySequenceNumber.get(sequenceNumber);
            if (expectedKeys == null) {
                assertTrue(
                        set.getEntriesWithSequenceNumber(sequenceNumber).isEmpty(), "set should not contain any keys");
            } else {
                final List<SequenceSetElement> keys = set.getEntriesWithSequenceNumber(sequenceNumber);
                assertEquals(expectedKeys.size(), keys.size(), "unexpected number of keys returned");
                for (final SequenceSetElement element : keys) {
                    assertTrue(expectedKeys.contains(element.key), "element not in expected set");
                    assertEquals(getSequenceNumber.apply(element.key), element.sequence, "unexpected sequence number");
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Simple Access Test")
    void simpleAccessTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 100);

        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / 5));
            assertEquals(i + 1, set.getSize(), "unexpected size");
        }

        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Single Shift Test")
    void singleShiftTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 100);
        assertEquals(0, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / keysPerSeq));
            assertEquals(i + 1, set.getSize(), "unexpected size");
        }

        set.shiftWindow(size / 2 / keysPerSeq);
        assertEquals(size / 2 / keysPerSeq, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(size / 2, set.getSize(), "unexpected size");

        validateSetContents(set, 0, 2 * size, key -> {
            if (key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("shiftWindow() With Callback Test")
    void shiftWindowWithCallbackTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 100);
        assertEquals(0, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / keysPerSeq));
            assertEquals(i + 1, set.getSize(), "unexpected size");
        }

        final Set<Integer> purgedKeys = new HashSet<>();
        set.shiftWindow(size / 2 / keysPerSeq, key -> {
            assertTrue(key.sequence < size / 2 / keysPerSeq, "key should not be purged");
            assertEquals(key.key / keysPerSeq, key.sequence, "unexpected sequence number for key");
            assertTrue(purgedKeys.add(key.key), "callback should be invoked once per key");
        });

        assertEquals(size / 2, purgedKeys.size(), "unexpected number of keys purged");

        assertEquals(size / 2 / keysPerSeq, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(size / 2, set.getSize(), "unexpected size");

        validateSetContents(set, 0, 2 * size, key -> {
            if (key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Upper/Lower Bound Test")
    void upperLowerBoundTest(final SetBuilder setBuilder) {
        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(5L, 5);

        assertEquals(5, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");
        assertEquals(9, set.getLastSequenceNumberInWindow(), "unexpected upper bound");

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / keysPerSeq));
        }

        validateSetContents(set, 0, 2 * size, key -> (long) key / keysPerSeq);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Shifting Window Test")
    void shiftingWindowTest(final SetBuilder setBuilder) {
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        int lowerBound = 0;

        final int capacity = 20;

        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply((long) lowerBound, capacity);

        for (int iteration = 0; iteration < 10; iteration++) {
            if (iteration % 2 == 0) {
                // shift the lower bound
                lowerBound++;
                set.shiftWindow(lowerBound);
            }

            assertEquals(lowerBound, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");
            assertEquals(
                    set.getLastSequenceNumberInWindow(),
                    set.getFirstSequenceNumberInWindow() + capacity - 1,
                    "unexpected upper bound");

            // Add a bunch of values. Values outside the window should be ignored.
            for (int i = lowerBound * keysPerSeq - 100;
                    i < set.getLastSequenceNumberInWindow() * keysPerSeq + 100;
                    i++) {

                set.add(new SequenceSetElement(i, i / keysPerSeq));
            }

            validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + size), key -> {
                if (key >= 0) {
                    return (long) key / keysPerSeq;
                } else {
                    // key is not present
                    return null;
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("clear() Test")
    void clearTest(final SetBuilder setBuilder) {

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 50;

        final int capacity = 5;

        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply((long) lowerBound, capacity);

        assertEquals(lowerBound, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < set.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            set.add(new SequenceSetElement(i, i / 5));
        }

        validateSetContents(
                set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> (long) key / keysPerSeq);

        // Shift the window.
        final int newLowerBound = lowerBound + 10;
        set.shiftWindow(newLowerBound);

        assertEquals(newLowerBound, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        for (int i = 0; i < set.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            set.add(new SequenceSetElement(i, i / 5));
        }

        validateSetContents(
                set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> (long) key / keysPerSeq);

        set.clear();

        // should revert to original bounds
        assertEquals(lowerBound, set.getFirstSequenceNumberInWindow(), "unexpected lower bound");

        assertEquals(0, set.getSize(), "set should be empty");

        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> null);

        // Reinserting values should work the same way as when the set was "fresh"
        for (int i = 0; i < set.getLastSequenceNumberInWindow() * keysPerSeq + 100; i++) {
            set.add(new SequenceSetElement(i, i / 5));
        }

        validateSetContents(
                set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> (long) key / keysPerSeq);
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("remove() Test")
    void removeTest(final SetBuilder setBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;

        int capacity = 5;

        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply((long) lowerBound, capacity);

        // removing values from an empty set shouldn't cause problems
        assertFalse(set.remove(new SequenceSetElement(-100, 0)), "value should not be in set");
        assertFalse(set.remove(new SequenceSetElement(0, 0)), "value should not be in set");
        assertFalse(set.remove(new SequenceSetElement(50, 0)), "value should not be in set");
        assertFalse(set.remove(new SequenceSetElement(100, 0)), "value should not be in set");

        // Validate removal of an existing value
        assertEquals(0, set.getSize(), "set should be empty");
        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> null);

        set.add(new SequenceSetElement(10, 2));
        assertTrue(set.remove(new SequenceSetElement(10, 2)), "value should have been removed");

        assertEquals(0, set.getSize(), "set should be empty");
        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> null);

        // Re-inserting after removal should work the same as regular insertion
        set.add(new SequenceSetElement(10, 2));
        assertTrue(set.contains(new SequenceSetElement(10, 2)), "should contain value");

        assertEquals(1, set.getSize(), "set should contain one thing");
        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> {
            if (key == 10) {
                return 2L;
            } else {
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("Replacing add() Test")
    void replacingPutTest(final SetBuilder setBuilder) {
        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        // The lowest permitted sequence number
        final int lowerBound = 0;

        final int capacity = 5;

        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply((long) lowerBound, capacity);

        assertTrue(set.add(new SequenceSetElement(10, 2)), "no value should currently be in set");

        assertEquals(1, set.getSize(), "set should contain one thing");
        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> {
            if (key == 10) {
                return 2L;
            } else {
                return null;
            }
        });

        assertFalse(set.add(new SequenceSetElement(10, 2)), "previous value should be returned");

        assertEquals(1, set.getSize(), "set should contain one thing");
        validateSetContents(set, 0, (int) (set.getLastSequenceNumberInWindow() * keysPerSeq + 100), key -> {
            if (key == 10) {
                return 2L;
            } else {
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("removeSequenceNumber() Test")
    void removeSequenceNumberTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 100);

        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / 5));
            assertEquals(i + 1, set.getSize(), "unexpected size");
        }

        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });

        // Remove sequence numbers that are not in the set
        set.removeSequenceNumber(-1000);
        set.removeSequenceNumber(1000);
        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });

        // Remove a sequence number that is in the set
        set.removeSequenceNumber(1);

        validateSetContents(set, -size, 2 * size, key -> {
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
        });

        // Removing the same sequence number a second time shouldn't have any ill effects
        set.removeSequenceNumber(1);

        validateSetContents(set, -size, 2 * size, key -> {
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
        });

        // It should be ok to re-insert into the removed sequence number
        set.add(new SequenceSetElement(5, 1));
        set.add(new SequenceSetElement(6, 1));
        set.add(new SequenceSetElement(7, 1));
        set.add(new SequenceSetElement(8, 1));
        set.add(new SequenceSetElement(9, 1));

        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("testConfiguration")
    @DisplayName("removeSequenceNumber() With Callback Test")
    void removeSequenceNumberWithCallbackTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 100);

        // The number of things inserted into the set
        final int size = 100;

        // The number of keys for each sequence number
        final int keysPerSeq = 5;

        for (int i = 0; i < size; i++) {
            set.add(new SequenceSetElement(i, i / 5));
            assertEquals(i + 1, set.getSize(), "unexpected size");
        }

        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });

        // Remove sequence numbers that are not in the set
        set.removeSequenceNumber(-1000, key -> fail("should not be called"));
        set.removeSequenceNumber(1000, key -> fail("should not be called"));
        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });

        // Remove a sequence number that is in the set
        final Set<Integer> removedKeys = new HashSet<>();
        set.removeSequenceNumber(1, key -> {
            assertEquals(1, key.sequence, "key should not be removed");
            assertEquals(key.key / keysPerSeq, key.sequence, "unexpected sequence number for key");
            assertTrue(removedKeys.add(key.key), "callback should be invoked once per key");
        });
        assertEquals(5, removedKeys.size(), "unexpected number of keys removed");

        validateSetContents(set, -size, 2 * size, key -> {
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
        });

        // Removing the same sequence number a second time shouldn't have any ill effects
        set.removeSequenceNumber(1, key -> fail("should not be called"));

        validateSetContents(set, -size, 2 * size, key -> {
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
        });

        // It should be ok to re-insert into the removed sequence number
        set.add(new SequenceSetElement(5, 1));
        set.add(new SequenceSetElement(6, 1));
        set.add(new SequenceSetElement(7, 1));
        set.add(new SequenceSetElement(8, 1));
        set.add(new SequenceSetElement(9, 1));

        validateSetContents(set, -size, 2 * size, key -> {
            if (key >= 0 && key < size) {
                return (long) key / keysPerSeq;
            } else {
                // key is not present
                return null;
            }
        });
    }

    @Test
    @DisplayName("Parallel SequenceSet Test")
    void parallelSequenceSetTest() throws InterruptedException {
        final Random random = new Random();

        final AtomicInteger lowerBound = new AtomicInteger(0);
        final int capacity = 100;

        final SequenceSet<SequenceSetElement> set =
                new ConcurrentSequenceSet<>(lowerBound.get(), capacity, SequenceSetElement::sequence);

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
                            sequenceNumber < set.getLastSequenceNumberInWindow() + 100;
                            sequenceNumber++) {

                        if (sequenceNumber < lowerBound.get() || sequenceNumber > set.getLastSequenceNumberInWindow()) {
                            final List<SequenceSetElement> keys = set.getEntriesWithSequenceNumber(sequenceNumber);
                            assertEquals(0, keys.size(), "no keys should be present for this round");
                        }
                    }

                    // shift the window
                    lowerBound.getAndAdd(5);
                    set.shiftWindow(lowerBound.get());
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
                                random.nextInt(lowerBound.get() - 50, (int) (set.getLastSequenceNumberInWindow() + 50));

                        if (choice < 0.5) {
                            // attempt to delete a value
                            final int key = random.nextInt(sequenceNumber * 10, sequenceNumber * 10 + 10);
                            set.remove(new SequenceSetElement(key, sequenceNumber));

                        } else if (choice < 0.999) {
                            // insert/replace a value
                            final int key = random.nextInt(sequenceNumber * 10, sequenceNumber * 10 + 10);
                            set.add(new SequenceSetElement(key, sequenceNumber));

                        } else {
                            // very rarely, delete an entire sequence number
                            set.removeSequenceNumber(sequenceNumber);
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
    @DisplayName("add() Return Value Test")
    void addReturnValueTest(final SetBuilder setBuilder) {
        final SequenceSet<SequenceSetElement> set = setBuilder.constructor.apply(0L, 10);

        assertTrue(set.add(new SequenceSetElement(0, 0)), "should return true if value is not present");
        assertFalse(set.add(new SequenceSetElement(0, 0)), "should return true if value is present");

        assertFalse(set.add(new SequenceSetElement(1, -1)), "should return false if value is outside window");
        assertFalse(set.add(new SequenceSetElement(2, 10)), "should return false if value is outside window");

        set.shiftWindow(1);
        assertTrue(set.add(new SequenceSetElement(2, 10)), "should return true now that window has shifted");
    }
}
