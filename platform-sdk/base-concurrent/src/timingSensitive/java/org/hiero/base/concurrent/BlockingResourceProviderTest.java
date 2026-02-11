// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.base.concurrent.test.fixtures.ConcurrentTesting;
import org.junit.jupiter.api.Test;

class BlockingResourceProviderTest {
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

    /**
     * After a consumer is interrupted in {@code waitForResource()}, the provider should still be in a usable
     * state: a full provide/consume cycle must succeed.
     */
    @Test
    void provideConsumeWorksAfterConsumerInterruption() throws InterruptedException {
        final BlockingResourceProvider<String> provider = new BlockingResourceProvider<>();
        final Duration timeout = Duration.ofSeconds(5);
        // First consumer blocks then gets interrupted
        Thread consumerThread = new Thread(() -> {
            try {
                // do not close the resource on purpose
                provider.waitForResource();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumerThread.start();
        // Wait until the consumer is blocked waiting for a resource
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!provider.isWaitingForResource()) {
            assertTrue(System.currentTimeMillis() < deadline, "Consumer never started waiting");
            Thread.sleep(10);
        }

        provider.acquireProvidePermit();
        consumerThread.interrupt();

        consumerThread = new Thread(() -> {
            try (final var value = provider.waitForResource()) {
                assertEquals("Hola", value.getResource());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumerThread.start();
        provider.provide("Hola");
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
