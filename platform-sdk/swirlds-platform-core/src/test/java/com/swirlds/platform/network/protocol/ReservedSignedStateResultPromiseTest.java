// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.network.protocol.ReservedSignedStateResultPromise.ReservedSignedStateResult;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for {@link ReservedSignedStateResultPromise}
 */
@Disabled
class ReservedSignedStateResultPromiseTest {

    /**
     * Tests that a single provider can successfully provide a resource and a consumer can retrieve it
     */
    @Test
    @Timeout(5)
    void testSingleProviderAndConsumer() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicReference<ReservedSignedStateResult> consumedState = new AtomicReference<>();
        final CountDownLatch consumerStarted = new CountDownLatch(1);
        final CountDownLatch consumerFinished = new CountDownLatch(1);

        // Start consumer thread
        final Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    consumedState.set(resource.getResource());
                }
                consumerFinished.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Wait for consumer to start waiting
        consumerStarted.await();
        Thread.sleep(100); // Give consumer time to block

        // Acquire permit and provide
        assertTrue(promise.acquire(), "Should be able to acquire permit when consumer is waiting");
        promise.resolveWithValue(mockState);

        // Wait for consumer to finish
        assertTrue(consumerFinished.await(2, TimeUnit.SECONDS), "Consumer should finish");
        assertEquals(
                mockState, consumedState.get().reservedSignedState(), "Consumer should receive the provided state");
    }

    /**
     * Tests that acquire returns false when no consumer is waiting
     */
    @Test
    void testAcquireFailsWhenNoConsumerWaiting() {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        assertFalse(promise.acquire(), "Should not be able to acquire permit when no consumer is waiting");
    }

    /**
     * Tests that only one provider can acquire the permit
     */
    @Test
    @Timeout(5)
    void testOnlyOneProviderCanAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final CountDownLatch consumerReady = new CountDownLatch(1);
        final CountDownLatch providersStarted = new CountDownLatch(3);
        final CountDownLatch testComplete = new CountDownLatch(1);

        // Start consumer
        final Thread consumer = new Thread(() -> {
            try {
                consumerReady.countDown();
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    assertNotNull(resource.getResource());
                }
                testComplete.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Wait for consumer to start
        consumerReady.await();
        Thread.sleep(100); // Give consumer time to block

        // Start multiple providers competing for the permit
        final ExecutorService executor = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                providersStarted.countDown();
                if (promise.acquire()) {
                    acquireCount.incrementAndGet();
                    try {
                        promise.resolveWithValue(mockState);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        providersStarted.await();
        assertTrue(testComplete.await(2, TimeUnit.SECONDS), "Test should complete");
        assertEquals(1, acquireCount.get(), "Only one provider should successfully acquire the permit");

        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Executor should terminate");
    }

    /**
     * Tests that a provider can release the permit if it cannot provide
     */
    @Test
    @Timeout(5)
    void testReleasePermitAllowsAnotherProviderToAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final CountDownLatch consumerReady = new CountDownLatch(1);
        final CountDownLatch firstProviderAcquired = new CountDownLatch(1);
        final CountDownLatch firstProviderReleased = new CountDownLatch(1);
        final CountDownLatch testComplete = new CountDownLatch(1);
        final AtomicBoolean secondProviderSucceeded = new AtomicBoolean(false);

        // Start consumer
        final Thread consumer = new Thread(() -> {
            try {
                consumerReady.countDown();
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    assertNotNull(resource.getResource());
                }
                testComplete.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Wait for consumer to start
        consumerReady.await();
        Thread.sleep(100); // Give consumer time to block

        // First provider acquires and releases
        final Thread firstProvider = new Thread(() -> {
            if (promise.acquire()) {
                firstProviderAcquired.countDown();
                promise.release();
                firstProviderReleased.countDown();
            }
        });
        firstProvider.start();

        firstProviderAcquired.await();
        firstProviderReleased.await();

        // Second provider can now acquire
        final Thread secondProvider = new Thread(() -> {
            try {
                // Give some time for the release to take effect
                Thread.sleep(50);
                if (promise.acquire()) {
                    secondProviderSucceeded.set(true);
                    promise.resolveWithValue(mockState);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        secondProvider.start();

        assertTrue(testComplete.await(2, TimeUnit.SECONDS), "Test should complete");
        assertTrue(secondProviderSucceeded.get(), "Second provider should successfully acquire after first released");
    }

    /**
     * Tests the tryBlock functionality which blocks further permits
     */
    @Test
    @Timeout(5)
    void testTryBlockPreventsAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();

        // Block the permit before consumer starts waiting
        assertTrue(promise.tryBlock(), "Should be able to block the permit");

        // Start a consumer
        final CountDownLatch consumerStarted = new CountDownLatch(1);
        final Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                promise.awaitResolution();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        consumerStarted.await();
        Thread.sleep(100); // Give consumer time to start waiting

        // Even though consumer is waiting, acquire should fail because permit is blocked
        assertFalse(promise.acquire(), "Should not be able to acquire when permit is blocked");

        // Release the block
        promise.release();

        // Now acquire should succeed
        assertTrue(promise.acquire(), "Should be able to acquire after releasing block");

        // Clean up
        promise.release();
        consumer.interrupt();
        consumer.join(1000);
    }

    /**
     * Tests multiple sequential provide/consume cycles
     */
    @Test
    @Timeout(10)
    void testMultipleSequentialCycles() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final int numCycles = 5;
        final AtomicInteger consumedCount = new AtomicInteger(0);
        final CountDownLatch allCyclesComplete = new CountDownLatch(1);

        // Consumer thread that consumes multiple resources
        final Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < numCycles; i++) {
                    try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                        assertNotNull(resource.getResource());
                        consumedCount.incrementAndGet();
                    }
                }
                allCyclesComplete.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Provider thread that provides multiple resources
        final Thread provider = new Thread(() -> {
            try {
                for (int i = 0; i < numCycles; i++) {
                    // Wait until we can acquire
                    while (!promise.acquire()) {
                        Thread.sleep(10);
                    }
                    final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
                    promise.resolveWithValue(mockState);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        provider.start();

        assertTrue(allCyclesComplete.await(5, TimeUnit.SECONDS), "All cycles should complete");
        assertEquals(numCycles, consumedCount.get(), "All resources should be consumed");

        provider.join(1000);
        consumer.join(1000);
    }

    /**
     * Tests that provide blocks until consumer releases the resource
     */
    @Test
    @Timeout(5)
    void testResolveWithValueBlocksUntilConsumerReleases() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicBoolean providerFinished = new AtomicBoolean(false);
        final CountDownLatch consumerStarted = new CountDownLatch(1);
        final CountDownLatch providerStarted = new CountDownLatch(1);
        final AtomicReference<LockedResource<ReservedSignedStateResult>> resourceRef = new AtomicReference<>();

        // Consumer that holds the resource for a while
        final Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution();
                resourceRef.set(resource);

                // Hold the resource for a bit
                Thread.sleep(200);

                // Provider should still be blocked at this point
                assertFalse(providerFinished.get(), "Provider should still be blocked");

                // Release the resource
                resource.close();

                // Give provider time to finish
                Thread.sleep(100);
                assertTrue(providerFinished.get(), "Provider should be finished after consumer releases");
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Wait for consumer to start
        consumerStarted.await();
        Thread.sleep(100); // Give consumer time to block

        // Provider
        final Thread provider = new Thread(() -> {
            try {
                providerStarted.countDown();
                assertTrue(promise.acquire(), "Should acquire permit");
                promise.resolveWithValue(mockState); // This will block until consumer releases
                providerFinished.set(true);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        provider.start();

        providerStarted.await();

        consumer.join(2000);
        provider.join(2000);
    }

    /**
     * Tests concurrent providers competing to provide resources
     */
    @Test
    @Timeout(15)
    void testMultipleProvidersCompeting() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final int numProviders = 5;
        final int numResources = 10;
        final AtomicInteger providedCount = new AtomicInteger(0);
        final AtomicInteger consumedCount = new AtomicInteger(0);
        final CountDownLatch allConsumed = new CountDownLatch(1);
        final CountDownLatch allProvided = new CountDownLatch(numProviders);

        // Consumer
        final Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < numResources; i++) {
                    try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                        assertNotNull(resource.getResource());
                        consumedCount.incrementAndGet();
                    }
                }
                allConsumed.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Multiple competing providers
        final ExecutorService executor = Executors.newFixedThreadPool(numProviders);
        for (int i = 0; i < numProviders; i++) {
            executor.submit(() -> {
                while (providedCount.get() < numResources) {
                    if (promise.acquire()) {
                        if (providedCount.get() < numResources) {
                            try {
                                final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
                                promise.resolveWithValue(mockState);
                                providedCount.incrementAndGet();
                                allProvided.countDown();
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        } else {
                            promise.release();
                            return;
                        }
                    }
                }
            });
        }

        assertTrue(allProvided.await(5, TimeUnit.SECONDS), "All resources should be provided");
        assertTrue(allConsumed.await(5, TimeUnit.SECONDS), "All resources should be consumed");
        assertEquals(numResources, consumedCount.get(), "Consumer should receive all resources");
        assertEquals(numResources, providedCount.get(), "Exactly numResources should be provided");

        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Executor should terminate");
    }

    /**
     * Tests that await returns null resource when provided null
     */
    @Test
    @Timeout(5)
    void testAwaitResolutionWithNullReservation() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState nullReservation = ReservedSignedState.createNullReservation();
        final AtomicReference<ReservedSignedStateResult> consumedState = new AtomicReference<>();
        final CountDownLatch consumerFinished = new CountDownLatch(1);

        // Start consumer
        final Thread consumer = new Thread(() -> {
            try {
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    consumedState.set(resource.getResource());
                }
                consumerFinished.countDown();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        Thread.sleep(100); // Give consumer time to start waiting

        // Provide null reservation
        assertTrue(promise.acquire(), "Should acquire permit");
        promise.resolveWithValue(nullReservation);

        assertTrue(consumerFinished.await(2, TimeUnit.SECONDS), "Consumer should finish");
        assertNotNull(consumedState.get(), "Consumer should receive the reservation");
        assertTrue(consumedState.get().reservedSignedState().isNull(), "Reservation should wrap null");
    }
}
