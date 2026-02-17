// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.base.concurrent.test.fixtures.ConcurrentTesting;
import org.junit.jupiter.api.Test;

class BlockingResourceProviderTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Tests the intended functionality of {@link BlockingResourceProvider}
     * <ul>
     *     <li>Starts up a number of providers and a single consumer</li>
     *     <li>The consumer waits for a resource and consumes it until the number of expected resources is met</li>
     *     <li>The providers fight over the permit and sometimes a resource and sometimes release the permit</li>
     * </ul>
     * We check the following:
     * <ul>
     *     <li>Before we provide a resource, we check if the previous one has already been consumed</li>
     *     <li>At the end, we check that each resource has been consumed exactly once</li>
     *     <li>That no exceptions have been thrown</li>
     *     <li>That all threads finished gracefully</li>
     * </ul>
     */
    @Test
    void testProvidersTakeTurns() throws ExecutionException, InterruptedException {
        final int numResources = 10;
        final int numThreads = 10;

        final ConcurrentTesting concurrentTesting = new ConcurrentTesting();
        final BlockingResourceProvider<Resource> provider = new BlockingResourceProvider<>();
        final AtomicReference<Resource> lastProvided = new AtomicReference<>();
        final ConcurrentMap<Resource, Resource> consumed = new ConcurrentHashMap<>();

        for (int i = 0; i < numThreads; i++) {
            concurrentTesting.addRunnable(new Provider(provider, lastProvided, consumed, i, numResources));
        }
        concurrentTesting.addRunnable(() -> {
            int numConsumed = 0;
            while (numConsumed < numThreads * numResources) {
                try (final LockedResource<Resource> lr = provider.waitForResource()) {
                    consumed.put(lr.getResource(), lr.getResource());
                    numConsumed++;
                }
            }
        });

        concurrentTesting.runForSeconds(5);

        assertEquals(
                numThreads * numResources, consumed.size(), "each resource was supposed to be consumed exactly once");
    }

    @Test
    void interruptWithResourceAlreadyDelivered() throws Exception {
        final BlockingResourceProvider<String> provider = new BlockingResourceProvider<>();

        Thread consumerThread = new Thread(() -> {
            try {
                provider.waitForResource();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumerThread.start();

        // Wait for consumer to be in await()
        final long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (!provider.isWaitingForResource()) {
            assertTrue(System.currentTimeMillis() < deadline, "Consumer never started waiting");
            Thread.sleep(10);
        }

        final LockedResource<String> resource = instrumentInterruptionDuringProvideMethod(provider, consumerThread);

        consumerThread.join(TIMEOUT);

        // If the fix works: resource.close() was called in the catch block,
        // which nulled the resource and unlocked. Verify the provider is usable.
        assertFalse(consumerThread.isAlive());
        assertNull(resource.getResource()); // resource was cleaned up
        assertFalse(provider.isWaitingForResource()); // and the provider is no longer waiting for a resource

        // After that, since the provider still holds the permit, it should be allowed to provide another value
        consumerThread = new Thread(() -> {
            try (final var value = provider.waitForResource()) {
                assertEquals("Hi", value.getResource());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumerThread.start();
        provider.provide("Hi");
        consumerThread.join(TIMEOUT);
    }

    /**
     * Simulates a {@link BlockingResourceProvider#provide(Object)} invocation
     * that was interrupted
     */
    private static @NonNull LockedResource<String> instrumentInterruptionDuringProvideMethod(
            final BlockingResourceProvider<String> provider, final Thread consumerThread)
            throws NoSuchFieldException, IllegalAccessException {
        // The following lines simulates the following scenario:
        // a provider has set a (non-null) resource, but the consumer's resourceProvided.await() favors the interrupt
        // over the signal.
        // Since that race can't be forced through the public API, using this trick
        // The provider acquires the permit
        assertTrue(provider.acquireProvidePermit());
        // The provider provides the value
        // Use reflection to get the lock and resource without triggering the condition
        Field lockField = BlockingResourceProvider.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(provider);

        Field resourceField = BlockingResourceProvider.class.getDeclaredField("resource");
        resourceField.setAccessible(true);
        LockedResource<String> resource = (LockedResource<String>) resourceField.get(provider);

        // Acquire the lock (possible because await() released it), set resource, release lock
        // Do NOT signal resourceProvided — the only wake-up will be the interrupt
        lock.lock();
        resource.setResource("delivered");
        lock.unlock();
        consumerThread.interrupt();
        return resource;
    }

    /**
     * Tests the scenario where a provider acquires the permit while the consumer is waiting,
     * but the consumer gets interrupted before the provider calls {@code provide()}.
     * The provider should return immediately without blocking and leave the provider in a usable state.
     */
    @Test
    void providerDoesNotBlockWhenConsumerFinishesBeforeProvide() throws InterruptedException {
        final BlockingResourceProvider<String> provider = new BlockingResourceProvider<>();

        // Start a consumer that waits for a resource
        final Thread consumerThread = new Thread(() -> {
            try {
                provider.waitForResource();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumerThread.start();

        // Wait until the consumer is blocked waiting for a resource
        final long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (!provider.isWaitingForResource()) {
            assertTrue(System.currentTimeMillis() < deadline, "Consumer never started waiting");
            Thread.sleep(10);
        }

        // Provider acquires the permit (succeeds because consumer is waiting)
        assertTrue(provider.acquireProvidePermit());

        // Consumer gets interrupted before the provider calls provide()
        consumerThread.interrupt();
        consumerThread.join(TIMEOUT);
        assertFalse(consumerThread.isAlive(), "Consumer thread should have finished");
        assertFalse(provider.isWaitingForResource(), "Consumer should no longer be waiting");

        // Provider calls provide() — should return immediately without blocking
        final Thread providerThread = new Thread(() -> {
            try {
                provider.provide("too late");
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        providerThread.start();
        providerThread.join(TIMEOUT);
        assertFalse(providerThread.isAlive(), "Provider should not be blocked");

        // Verify the provider is in a usable state: a full provide/consume cycle must succeed
        final Thread nextConsumer = new Thread(() -> {
            try (final var value = provider.waitForResource()) {
                assertEquals("next", value.getResource());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        nextConsumer.start();

        // Wait for the next consumer to be waiting
        final long deadline2 = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (!provider.isWaitingForResource()) {
            assertTrue(System.currentTimeMillis() < deadline2, "Next consumer never started waiting");
            Thread.sleep(10);
        }

        assertTrue(provider.acquireProvidePermit(), "Permit should be available for the next cycle");
        provider.provide("next");
        nextConsumer.join(TIMEOUT);
        assertFalse(nextConsumer.isAlive(), "Next consumer should have finished");
    }

    private record Resource(int threadId, int sequence) {}

    private static final class Provider implements ThrowingRunnable {
        private final BlockingResourceProvider<Resource> provider;
        private final AtomicReference<Resource> lastProvided;
        private final ConcurrentMap<Resource, Resource> consumed;
        private final int threadId;
        private final int numResources;

        public Provider(
                final BlockingResourceProvider<Resource> provider,
                final AtomicReference<Resource> lastProvided,
                final ConcurrentMap<Resource, Resource> consumed,
                final int threadId,
                final int numResources) {
            this.provider = provider;
            this.lastProvided = lastProvided;
            this.consumed = consumed;
            this.threadId = threadId;
            this.numResources = numResources;
        }

        @Override
        public void run() throws InterruptedException {
            final Random r = new Random();
            int seq = 0;
            while (seq < numResources) {
                if (!provider.acquireProvidePermit()) {
                    continue;
                }
                if (r.nextBoolean()) {
                    provider.releaseProvidePermit();
                    continue;
                }
                final Resource resource = new Resource(threadId, seq);
                final Resource last = lastProvided.getAndSet(resource);
                if (last != null && !consumed.containsKey(last)) {
                    throw new RuntimeException(String.format(
                            "last (%s) resource provided has not been consumed. current: %s", last, resource));
                }
                provider.provide(resource);
                seq++;
            }
        }
    }
}
