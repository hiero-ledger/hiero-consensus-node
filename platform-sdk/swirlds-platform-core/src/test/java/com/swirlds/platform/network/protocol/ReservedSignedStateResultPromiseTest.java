// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.network.protocol.ReservedSignedStateResultPromise.ReservedSignedStateResult;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.locks.locked.LockedResource;
import org.hiero.base.concurrent.test.fixtures.ConsumerWithCompletionControl;
import org.hiero.base.concurrent.test.fixtures.RunnableCompletionControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for {@link ReservedSignedStateResultPromise}
 */
class ReservedSignedStateResultPromiseTest {
    public static final Duration AWAIT_MAX_DURATION = Duration.ofSeconds(10);
    /**
     * Tests that a single provider can successfully provide a resource and a consumer can retrieve it
     */
    @Test
    void testSingleProviderAndConsumer() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicReference<ReservedSignedStateResult> consumedState = new AtomicReference<>();

        // Start consumer thread
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    consumedState.set(resource.getResource());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        // Wait for consumer to start waiting
        Thread.sleep(100); // Give consumer time to block

        // Acquire permit and provide
        assertTrue(promise.acquire(), "Should be able to acquire permit when consumer is waiting");
        promise.resolveWithValue(mockState);

        // Wait for consumer to finish
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
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
    void testOnlyOneProviderCanAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicInteger acquireCount = new AtomicInteger(0);

        // Start consumer
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    assertNotNull(resource.getResource());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        final var providers = new ArrayList<RunnableCompletionControl>();
        // Start multiple providers competing for the permit
        for (int i = 0; i < 3; i++) {
            providers.add(RunnableCompletionControl.unblocked(() -> {
                if (promise.acquire()) {
                    acquireCount.incrementAndGet();
                    try {
                        promise.resolveWithValue(mockState);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        }
        Thread.sleep(100); // Give consumer time to block
        providers.parallelStream().forEach(RunnableCompletionControl::start);
        providers.forEach(c -> c.waitIsFinished(AWAIT_MAX_DURATION));
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
        assertEquals(1, acquireCount.get(), "Only one provider should successfully acquire the permit");
    }

    /**
     * Tests that a provider can release the permit if it cannot provide
     */
    @Test
    void testReleasePermitAllowsAnotherProviderToAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final CountDownLatch firstProviderAcquired = new CountDownLatch(1);
        final CountDownLatch firstProviderReleased = new CountDownLatch(1);

        // Start consumer
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    assertNotNull(resource.getResource());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        Thread.sleep(100); // Give consumer time to block

        // First provider acquires and releases
        final var firstProvider = RunnableCompletionControl.unblocked(() -> {
            if (promise.acquire()) {
                firstProviderAcquired.countDown();
                promise.release();
                firstProviderReleased.countDown();
            }
        });

        // Second provider can now acquire
        final var secondProvider = RunnableCompletionControl.unblocked(() -> {
            try {
                // Give some time for the release to take effect
                Thread.sleep(50);
                if (promise.acquire()) {
                    promise.resolveWithValue(mockState);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        firstProvider.start();
        firstProviderAcquired.await();
        secondProvider.start();
        firstProviderReleased.await();

        firstProvider.waitIsFinished(AWAIT_MAX_DURATION);
        secondProvider.waitIsFinished(AWAIT_MAX_DURATION);
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
    }

    /**
     * Tests the tryBlock functionality which blocks further permits
     */
    @Test
    void testTryBlockPreventsAcquire() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();

        // Block the permit before consumer starts waiting
        assertTrue(promise.tryBlock(), "Should be able to block the permit");

        // Start a consumer
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                promise.awaitResolution();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var consumerThread = consumer.start();

        // Even though consumer is waiting, acquire should fail because permit is blocked
        assertFalse(promise.acquire(), "Should not be able to acquire when permit is blocked");
        Thread.sleep(100); // Give consumer time to start waiting

        // Release the block
        promise.release();

        // Now acquire should succeed
        assertTrue(promise.acquire(), "Should be able to acquire after releasing block");

        // Clean up
        promise.release();
        consumerThread.interrupt();
    }

    /**
     * Tests multiple sequential provide/consume cycles
     */
    @Test
    void testMultipleSequentialCycles() {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final int numCycles = 5;
        final AtomicInteger consumedCount = new AtomicInteger(0);

        // Consumer thread that consumes multiple resources
        final var consumer = ConsumerWithCompletionControl.<Void>unblocked(ignored -> {
            try {

                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    assertNotNull(resource.getResource());
                    consumedCount.incrementAndGet();
                }

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.executionControl().unblock();

        // Provider thread that provides multiple resources
        final var provider = RunnableCompletionControl.unblocked(() -> {
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
        for (int i = 0; i < numCycles; i++) {
            consumer.accept(null);
        }
        provider.waitIsFinished(AWAIT_MAX_DURATION);
        consumer.executionControl().await(numCycles, AWAIT_MAX_DURATION);
        assertEquals(numCycles, consumedCount.get(), "All resources should be consumed");
    }

    /**
     * Tests that provide blocks until consumer releases the resource
     */
    @Test
    void testResolveWithValueBlocksUntilConsumerReleases() throws InterruptedException {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
        final AtomicReference<LockedResource<ReservedSignedStateResult>> resourceRef = new AtomicReference<>();

        // Consumer that holds the resource for a while
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution();
                resourceRef.set(resource);
                // Release the resource
                resource.close();

                // Give provider time to finish
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        final var provider = ConsumerWithCompletionControl.<Void>blocked(c -> {
            try {
                assertTrue(promise.acquire(), "Should acquire permit");
                // Hold the resource for a bit
                Thread.sleep(200);
                promise.resolveWithValue(mockState); // This will block until consumer releases
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // Wait for consumer to start
        Thread.sleep(100); // Give consumer time to block
        provider.executionControl().unblock();
        provider.accept(null);
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
        provider.executionControl().await(1, AWAIT_MAX_DURATION);
    }

    /**
     * Tests concurrent providers competing to provide resources
     */
    @Test
    void testMultipleProvidersCompeting() {
        final ReservedSignedStateResultPromise promise = new ReservedSignedStateResultPromise();
        final int numProviders = 5;
        final int numResources = 10;
        final AtomicInteger providedCount = new AtomicInteger(0);
        final AtomicInteger consumedCount = new AtomicInteger(0);

        // Consumer
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                for (int i = 0; i < numResources; i++) {
                    try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                        assertNotNull(resource.getResource());
                        consumedCount.incrementAndGet();
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var providers = new ArrayList<RunnableCompletionControl>();
        for (int i = 0; i < numProviders; i++) {
            providers.add(RunnableCompletionControl.unblocked(() -> {
                while (providedCount.get() < numResources) {
                    if (promise.acquire()) {
                        if (providedCount.get() < numResources) {
                            try {
                                final ReservedSignedState mockState = ReservedSignedState.createNullReservation();
                                promise.resolveWithValue(mockState);
                                providedCount.incrementAndGet();
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
            }));
        }

        consumer.start();
        providers.forEach(RunnableCompletionControl::start);
        providers.stream().parallel().forEach(c -> c.waitIsFinished(AWAIT_MAX_DURATION));
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
        assertEquals(numResources, consumedCount.get(), "Consumer should receive all resources");
        assertEquals(numResources, providedCount.get(), "Exactly numResources should be provided");
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

        // Start consumer
        final var consumer = RunnableCompletionControl.unblocked(() -> {
            try {
                try (final LockedResource<ReservedSignedStateResult> resource = promise.awaitResolution()) {
                    consumedState.set(resource.getResource());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        Thread.sleep(100); // Give consumer time to start waiting

        // Provide null reservation
        assertTrue(promise.acquire(), "Should acquire permit");
        promise.resolveWithValue(nullReservation);
        consumer.waitIsFinished(AWAIT_MAX_DURATION);
        assertNotNull(consumedState.get(), "Consumer should receive the reservation");
        assertTrue(consumedState.get().reservedSignedState().isNull(), "Reservation should wrap null");
    }
}
