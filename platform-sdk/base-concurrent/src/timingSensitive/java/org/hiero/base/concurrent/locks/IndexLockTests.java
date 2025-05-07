// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.AssertionUtils;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("IndexLock Tests")
class IndexLockTests {

    private static Object createObjectWithHashCode(final int hashCode) {
        return new Object() {
            @Override
            public int hashCode() {
                return hashCode;
            }
        };
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Same Index Blocks")
    void sameIndexBlocks() throws InterruptedException {
        final int size = 100;
        final int index = 22;
        final IndexLock lock = Locks.createIndexLock(size);
        // an objects hashcode should work the same way as an index
        final Object object = createObjectWithHashCode(index);

        final AtomicBoolean threadIsLocked = new AtomicBoolean();

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    lock.lock(object);
                    threadIsLocked.set(true);
                    lock.unlock(index);
                })
                .build();

        lock.lock(index);
        thread.start();

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        assertFalse(threadIsLocked.get(), "lock should be blocked");

        lock.unlock(object);

        AssertionUtils.assertEventuallyTrue(
                threadIsLocked::get, Duration.ofSeconds(1), "thread should have been able to acquire lock");
    }

    /**
     * Although it is possible for different indexes to acquire the same lock, if the requested indices are less
     * than hte total size of the array then they will not block.
     */
    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Different Index Does Not Block")
    void differentIndexDoesNotBlock() throws InterruptedException {
        final int size = 100;
        final int index = 22;
        final IndexLock lock = Locks.createIndexLock(size);

        final AtomicBoolean threadIsLocked = new AtomicBoolean();

        lock.lock(index);

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    lock.lock(index + 1);
                    threadIsLocked.set(true);
                    lock.unlock(index + 1);
                })
                .build();

        thread.start();

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        assertTrue(threadIsLocked.get(), "thread should have been able to acquire lock");

        lock.unlock(index);
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Same Index Autocloseable")
    void sameIndexBlocksAutocloseable() throws InterruptedException {
        final int size = 100;
        final int index = 22;
        // an objects hashcode should work the same way as an index
        final Object object = createObjectWithHashCode(index);
        final IndexLock lock = Locks.createIndexLock(size);

        final AtomicBoolean threadIsLocked = new AtomicBoolean();

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try (final Locked autoLock = lock.autoLock(object)) {
                        threadIsLocked.set(true);
                    }
                })
                .build();

        try (final Locked autoLock = lock.autoLock(index)) {
            thread.start();

            // Give the thread time to acquire the lock if it can
            MILLISECONDS.sleep(20);

            assertFalse(threadIsLocked.get(), "lock should be blocked");
        }

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        assertTrue(threadIsLocked.get(), "thread should have been able to acquire lock");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Negative Index Equivalent")
    void negativeIndex() throws InterruptedException {
        final int size = 100;
        final int index = 22;
        final IndexLock lock = Locks.createIndexLock(size);
        // an objects hashcode should work the same way as an index
        final Object object = createObjectWithHashCode(-index);

        final AtomicBoolean threadIsLocked = new AtomicBoolean();

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    lock.lock(index);
                    threadIsLocked.set(true);
                    lock.unlock(index);
                })
                .build();

        lock.lock(index);
        thread.start();

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        assertFalse(threadIsLocked.get(), "lock should be blocked");

        lock.unlock(object);

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        assertTrue(threadIsLocked.get(), "thread should have been able to acquire lock");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("All Locks Initialized")
    void allLocksInitialized() {
        final int size = 100;
        final IndexLock lock = Locks.createIndexLock(size);

        for (int i = 0; i < size * 1000; i++) {
            final int index = i;
            assertDoesNotThrow(
                    () -> lock.lock(index), "If a lock is initialized it should not throw a null pointer exception");
            lock.unlock(i);
        }
    }

    @Test
    @DisplayName("fullLock() Test")
    void fullLockTest() throws InterruptedException {
        final int size = 10;
        final IndexLock lock = Locks.createIndexLock(size);

        lock.fullyLock();

        final List<Thread> threads = new LinkedList<>();
        for (int index = 0; index < size; index++) {

            final int finalIndex = index;
            threads.add(new ThreadConfiguration(getStaticThreadManager())
                    .setThreadName("background-locker")
                    .setRunnable(() -> {
                        lock.lock(finalIndex);
                        lock.unlock(finalIndex);
                    })
                    .build(true));
        }

        // Give the thread time to acquire the lock if it can
        MILLISECONDS.sleep(20);

        threads.forEach(thread -> assertTrue(thread.isAlive(), "thread should be blocked"));

        lock.fullyUnlock();

        assertEventuallyDoesNotThrow(
                () -> threads.forEach(thread -> assertFalse(thread.isAlive(), "thread should be unblocked by now")),
                Duration.ofSeconds(1),
                "all threads should be unblocked by now");
    }

    @Test
    @DisplayName("autoFullLock() Test")
    void autoFullLockTest() throws InterruptedException {
        final int size = 10;
        final IndexLock lock = Locks.createIndexLock(size);

        final List<Thread> threads = new LinkedList<>();
        try (Locked locked = lock.autoFullLock()) {
            for (int index = 0; index < size; index++) {

                final int finalIndex = index;
                threads.add(new ThreadConfiguration(getStaticThreadManager())
                        .setThreadName("background-locker")
                        .setRunnable(() -> {
                            lock.lock(finalIndex);
                            lock.unlock(finalIndex);
                        })
                        .build(true));
            }

            // Give the thread time to acquire the lock if it can
            MILLISECONDS.sleep(20);

            threads.forEach(thread -> assertTrue(thread.isAlive(), "thread should be blocked"));
        }

        assertEventuallyDoesNotThrow(
                () -> threads.forEach(thread -> assertFalse(thread.isAlive(), "thread should be unblocked by now")),
                Duration.ofSeconds(1),
                "all threads should be unblocked by now");
    }
}
