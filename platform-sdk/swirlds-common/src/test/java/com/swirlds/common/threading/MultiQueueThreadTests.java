// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.AssertionUtils;
import com.swirlds.common.threading.framework.BlockingQueueInserter;
import com.swirlds.common.threading.framework.MultiQueueThread;
import com.swirlds.common.threading.framework.config.MultiQueueThreadConfiguration;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MultiQueueThread Tests")
class MultiQueueThreadTests {

    @Test
    @DisplayName("add() Test")
    void addTest() {

        final List<Object> objects = new ArrayList<>();
        final AtomicInteger intCount = new AtomicInteger(0);
        final AtomicInteger doubleCount = new AtomicInteger(0);
        final AtomicInteger stringCount = new AtomicInteger(0);

        final MultiQueueThread multiQueueThread = new MultiQueueThreadConfiguration(
                        AdHocThreadManager.getStaticThreadManager())
                .setThreadName("test")
                .setCapacity(5)
                .addHandler(Integer.class, (final Integer i) -> {
                    intCount.getAndIncrement();
                    objects.add(i);
                })
                .addHandler(Double.class, (d) -> {
                    doubleCount.getAndIncrement();
                    objects.add(d);
                })
                .addHandler(String.class, (s) -> {
                    stringCount.getAndIncrement();
                    objects.add(s);
                })
                .build();

        final BlockingQueueInserter<Integer> intInserter = multiQueueThread.getInserter(Integer.class);
        final BlockingQueueInserter<Double> doubleInserter = multiQueueThread.getInserter(Double.class);
        final BlockingQueueInserter<String> stringInserter = multiQueueThread.getInserter(String.class);

        // We use type erasure to "cheat" and make all the inserters the same object.
        assertSame(intInserter, doubleInserter);
        assertSame(intInserter, stringInserter);

        assertTrue(intInserter.add(1));
        assertTrue(doubleInserter.add(2.0));
        assertTrue(stringInserter.add("3"));
        assertTrue(intInserter.add(4));
        assertTrue(doubleInserter.add(5.0));
        assertThrows(IllegalStateException.class, () -> stringInserter.add("6"));

        multiQueueThread.start();
        // By default, stop will wait until all objects in the queue have been processed.
        multiQueueThread.stop();

        assertEquals(2, intCount.get());
        assertEquals(1, objects.get(0));
        assertEquals(4, objects.get(3));
        assertEquals(2, doubleCount.get());
        assertEquals(2.0, objects.get(1));
        assertEquals(5.0, objects.get(4));
        assertEquals(1, stringCount.get());
        assertEquals("3", objects.get(2));
    }

    @Test
    @DisplayName("offer() Test")
    void offerTest() {
        final List<Object> objects = new ArrayList<>();
        final AtomicInteger intCount = new AtomicInteger(0);
        final AtomicInteger doubleCount = new AtomicInteger(0);
        final AtomicInteger stringCount = new AtomicInteger(0);

        final MultiQueueThread multiQueueThread = new MultiQueueThreadConfiguration(
                        AdHocThreadManager.getStaticThreadManager())
                .setThreadName("test")
                .setCapacity(5)
                .addHandler(Integer.class, (final Integer i) -> {
                    intCount.getAndIncrement();
                    objects.add(i);
                })
                .addHandler(Double.class, (d) -> {
                    doubleCount.getAndIncrement();
                    objects.add(d);
                })
                .addHandler(String.class, (s) -> {
                    stringCount.getAndIncrement();
                    objects.add(s);
                })
                .build();

        final BlockingQueueInserter<Integer> intInserter = multiQueueThread.getInserter(Integer.class);
        final BlockingQueueInserter<Double> doubleInserter = multiQueueThread.getInserter(Double.class);
        final BlockingQueueInserter<String> stringInserter = multiQueueThread.getInserter(String.class);

        // We use type erasure to "cheat" and make all the inserters the same object.
        assertSame(intInserter, doubleInserter);
        assertSame(intInserter, stringInserter);

        assertTrue(intInserter.offer(1));
        assertTrue(doubleInserter.offer(2.0));
        assertTrue(stringInserter.offer("3"));
        assertTrue(intInserter.offer(4));
        assertTrue(doubleInserter.offer(5.0));
        assertFalse(stringInserter.offer("6"));

        multiQueueThread.start();
        // By default, stop will wait until all objects in the queue have been processed.
        multiQueueThread.stop();

        assertEquals(2, intCount.get());
        assertEquals(1, objects.get(0));
        assertEquals(4, objects.get(3));
        assertEquals(2, doubleCount.get());
        assertEquals(2.0, objects.get(1));
        assertEquals(5.0, objects.get(4));
        assertEquals(1, stringCount.get());
        assertEquals("3", objects.get(2));
    }

    @Test
    @DisplayName("offer() With Timeout Test")
    void offerWithTimeoutTest() throws InterruptedException {
        final List<Object> objects = new ArrayList<>();
        final AtomicInteger intCount = new AtomicInteger(0);
        final AtomicInteger doubleCount = new AtomicInteger(0);
        final AtomicInteger stringCount = new AtomicInteger(0);

        final MultiQueueThread multiQueueThread = new MultiQueueThreadConfiguration(
                        AdHocThreadManager.getStaticThreadManager())
                .setThreadName("test")
                .setCapacity(5)
                .addHandler(Integer.class, (final Integer i) -> {
                    intCount.getAndIncrement();
                    objects.add(i);
                })
                .addHandler(Double.class, (d) -> {
                    doubleCount.getAndIncrement();
                    objects.add(d);
                })
                .addHandler(String.class, (s) -> {
                    stringCount.getAndIncrement();
                    objects.add(s);
                })
                .build();

        final BlockingQueueInserter<Integer> intInserter = multiQueueThread.getInserter(Integer.class);
        final BlockingQueueInserter<Double> doubleInserter = multiQueueThread.getInserter(Double.class);
        final BlockingQueueInserter<String> stringInserter = multiQueueThread.getInserter(String.class);

        // We use type erasure to "cheat" and make all the inserters the same object.
        assertSame(intInserter, doubleInserter);
        assertSame(intInserter, stringInserter);

        final Instant start = Instant.now();
        assertTrue(intInserter.offer(1, 1, SECONDS));
        assertTrue(doubleInserter.offer(2.0, 1, SECONDS));
        assertTrue(stringInserter.offer("3", 1, SECONDS));
        assertTrue(intInserter.offer(4, 1, SECONDS));
        assertTrue(doubleInserter.offer(5.0, 1, SECONDS));
        final Instant fullQueue = Instant.now();
        assertFalse(stringInserter.offer("6", 1, SECONDS));
        final Instant end = Instant.now();

        // Inserting the first five things should be very fast.
        assertTrue(Duration.between(start, fullQueue).toMillis() < 100);
        // Inserting the sixth thing should take at least 1 second.
        // Fuzz the numbers a little just in case there is some clock jitter
        // (not sure if possible, too lazy to research).
        assertTrue(Duration.between(fullQueue, end).toMillis() >= 900);

        multiQueueThread.start();
        // By default, stop will wait until all objects in the queue have been processed.
        multiQueueThread.stop();

        assertEquals(2, intCount.get());
        assertEquals(1, objects.get(0));
        assertEquals(4, objects.get(3));
        assertEquals(2, doubleCount.get());
        assertEquals(2.0, objects.get(1));
        assertEquals(5.0, objects.get(4));
        assertEquals(1, stringCount.get());
        assertEquals("3", objects.get(2));
    }

    @Test
    @DisplayName("put() Test")
    void putTest() throws InterruptedException {
        final List<Object> objects = new ArrayList<>();
        final AtomicInteger intCount = new AtomicInteger(0);
        final AtomicInteger doubleCount = new AtomicInteger(0);
        final AtomicInteger stringCount = new AtomicInteger(0);

        final MultiQueueThread multiQueueThread = new MultiQueueThreadConfiguration(
                        AdHocThreadManager.getStaticThreadManager())
                .setThreadName("test")
                .setCapacity(5)
                .addHandler(Integer.class, (final Integer i) -> {
                    intCount.getAndIncrement();
                    objects.add(i);
                })
                .addHandler(Double.class, (d) -> {
                    doubleCount.getAndIncrement();
                    objects.add(d);
                })
                .addHandler(String.class, (s) -> {
                    stringCount.getAndIncrement();
                    objects.add(s);
                })
                .build();

        final BlockingQueueInserter<Integer> intInserter = multiQueueThread.getInserter(Integer.class);
        final BlockingQueueInserter<Double> doubleInserter = multiQueueThread.getInserter(Double.class);
        final BlockingQueueInserter<String> stringInserter = multiQueueThread.getInserter(String.class);

        // We use type erasure to "cheat" and make all the inserters the same object.
        assertSame(intInserter, doubleInserter);
        assertSame(intInserter, stringInserter);

        AssertionUtils.completeBeforeTimeout(
                () -> {
                    intInserter.put(1);
                    doubleInserter.put(2.0);
                    stringInserter.put("3");
                    intInserter.put(4);
                    doubleInserter.put(5.0);
                },
                Duration.ofSeconds(1),
                "should have been able to quickly insert 5 objects");

        final CountDownLatch latch = new CountDownLatch(1);
        new ThreadConfiguration(AdHocThreadManager.getStaticThreadManager())
                .setThreadName("test2")
                .setRunnable(() -> {
                    try {
                        stringInserter.put("6");
                        latch.countDown();
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build(true);

        // The insertion of the last element should be blocked.
        // After a second, it should still not have been inserted.
        SECONDS.sleep(1);
        assertEquals(1, latch.getCount());

        // Starting the queue will free capacity and the last element will be added.
        multiQueueThread.start();
        latch.await();
        // By default, stop will wait until all objects in the queue have been processed.
        multiQueueThread.stop();

        assertEquals(2, intCount.get());
        assertEquals(1, objects.get(0));
        assertEquals(4, objects.get(3));
        assertEquals(2, doubleCount.get());
        assertEquals(2.0, objects.get(1));
        assertEquals(5.0, objects.get(4));
        assertEquals(2, stringCount.get());
        assertEquals("3", objects.get(2));
        assertEquals("6", objects.get(5));
    }
}
