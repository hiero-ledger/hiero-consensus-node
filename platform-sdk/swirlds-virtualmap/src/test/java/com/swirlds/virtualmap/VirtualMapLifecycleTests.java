// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

class VirtualMapLifecycleTests {

    private static final Random RANDOM = new SecureRandom();

    private static final VirtualMap<TestKey, TestValue> TERMINATE_QUERY = new VirtualMap<>(CONFIGURATION);

    /**
     * This hammer test will have one thread querying maps while another thread is modifying
     * newer copies in parallel to the query thread.
     *
     * @throws InterruptedException
     * 		Just in case.
     */
    @Test
    @Tags({@Tag("VirtualMerkle"), @Tag("VMAP-016")})
    @DisplayName("Main thread mutates vm while background thread queries immutable vm")
    void queryCopyWhileMutatingOriginal() throws InterruptedException, ExecutionException, TimeoutException {
        final VirtualMap<TestKey, TestValue> map = createMap();
        final AtomicReference<VirtualMap<TestKey, TestValue>> mutableCopy = new AtomicReference<>(map);
        final BlockingQueue<VirtualMap<TestKey, TestValue>> immutableCopies = new LinkedBlockingQueue<>();
        final int totalTransactions = 10_000;
        final ExecutorService service = Executors.newSingleThreadExecutor();
        try {
            // Start the background thread that will query immutable copies
            final Future<?> queryFuture = service.submit(() -> {
                try {
                    queryVirtualMap(immutableCopies);
                } catch (final Exception ex) {
                    fail(ex);
                }
            });

            // First, create a bunch of stuff. Then update a bunch of stuff. Then delete a bunch of stuff.
            executeTransactions(totalTransactions, this::createKeyValue, immutableCopies, mutableCopy);
            executeTransactions(totalTransactions, this::updateKeyValue, immutableCopies, mutableCopy);
            executeTransactions(totalTransactions / 3, this::deleteKeyValue, immutableCopies, mutableCopy);

            // Now add a tombstone copy so the background query thread knows nothing else is coming and can quit.
            immutableCopies.add(TERMINATE_QUERY);
            // Wait for termination. It really shouldn't take this long.
            queryFuture.get(30, TimeUnit.SECONDS);
        } finally {
            // Release the mutable copy and shutdown the executor.
            mutableCopy.get().release();
            service.shutdown();
        }
    }

    private void deleteKeyValue(final int index, final AtomicReference<VirtualMap<TestKey, TestValue>> atomicMap) {
        final TestKey key = new TestKey(index);
        atomicMap.get().remove(key);
    }

    private void updateKeyValue(final int index, final AtomicReference<VirtualMap<TestKey, TestValue>> atomicMap) {
        final TestKey key = new TestKey(index);
        final TestValue value = new TestValue(index + RANDOM.nextInt());
        atomicMap.get().put(key, value);
    }

    private void createKeyValue(final int index, final AtomicReference<VirtualMap<TestKey, TestValue>> atomicMap) {
        final TestKey key = new TestKey(index);
        final TestValue value = new TestValue(index);
        atomicMap.get().put(key, value);
    }

    private void executeTransactions(
            final int totalTransactions,
            final BiConsumer<Integer, AtomicReference<VirtualMap<TestKey, TestValue>>> executor,
            final BlockingQueue<VirtualMap<TestKey, TestValue>> queue,
            final AtomicReference<VirtualMap<TestKey, TestValue>> atomicMap) {
        final int roundCycle = totalTransactions / 100;
        int transactionsCounter = 0;
        while (transactionsCounter < totalTransactions) {
            executor.accept(transactionsCounter, atomicMap);
            transactionsCounter++;
            if (transactionsCounter != 0 && transactionsCounter % roundCycle == 0) {
                final VirtualMap<TestKey, TestValue> immutableMap = atomicMap.get();
                atomicMap.set(immutableMap.copy());
                queue.add(immutableMap);
            }
        }
    }

    private void queryVirtualMap(final BlockingQueue<VirtualMap<TestKey, TestValue>> queue)
            throws InterruptedException {
        final Random random = new SecureRandom();
        try {
            while (true) {
                final VirtualMap<TestKey, TestValue> map = queue.take();
                if (map == TERMINATE_QUERY) {
                    map.release();
                    return;
                }
                do {
                    if (map.isEmpty()) {
                        break;
                    }

                    final long size = map.size();
                    assertTrue(map.isImmutable(), "Query on immutable copies only");
                    assertFalse(map.isDestroyed(), "Map shouldn't be destroyed yet");
                    TestValue value;
                    do {
                        final long keyIndex = random.nextInt((int) size);
                        final TestKey key = new TestKey(keyIndex);
                        value = map.get(key);
                    } while (value == null);
                } while (queue.isEmpty());
                map.release();
            }
        } catch (final InterruptedException ex) {
            while (!queue.isEmpty()) {
                final VirtualMap<TestKey, TestValue> map = queue.take();
                map.release();
            }
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
