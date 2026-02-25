// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.hiero.base.concurrent.locks.internal.AcquiredResource;
import org.hiero.base.concurrent.locks.locked.LockedResource;

/**
 * Allows a blocking ceremony between multiple provider threads and a single consumer thread.
 *
 * <p>This class implements the interactions of a continuous cycle of deliver-consume steps. In each cycle the consumer blocks
 * waiting for a resource, providers attempt to acquire a single permit, and the provider that acquires
 * it delivers a resource. The provider then blocks until the consumer is done with it. Once the consumer
 * closes the resource, both sides are released and the next cycle can begin.
 *
 * <h2>Provider usage</h2>
 * <ol>
 *     <li>{@link #acquireProvidePermit()} — returns {@code true} if this provider acquired the permit</li>
 *     <li>If the provider decides it cannot deliver, call {@link #releaseProvidePermit()} to release the
 *         permit so others can attempt to acquire it</li>
 *     <li>Otherwise call {@link #provide(Object)} — this blocks until the consumer finishes with the resource</li>
 * </ol>
 *
 * <h2>Consumer usage</h2>
 * <ol>
 *     <li>{@link #waitForResource()} — blocks until a provider delivers; returns a {@link LockedResource}</li>
 *     <li>Use the resource</li>
 *     <li>Close the {@link LockedResource} — this unblocks the provider and allows the next cycle to begin</li>
 * </ol>
 *
 * <h2>Interruption</h2>
 * <p>If the consumer is interrupted while waiting, any provider that already delivered a resource is
 * unblocked so it can complete gracefully. The consumer re-throws {@link InterruptedException} discarding any provided resource.
 *
 * @implNote Part of the necessary lifecycle for multiple instances of this class to work correctly is located outside this class.
 *   Once a resource is obtained it is very important that the client code diligently closes it in all cases (even errors) after using it,
 *   not doing so can leave to situations where providers could block indefinitely.
 * @param <T> the type of resource provided
 */
public class BlockingResourceProvider<T> {
    /** ensures only one provider acquires the permit per cycle */
    private final Semaphore providePermit = new Semaphore(1);
    /** guards the resource handoff between provider and consumer */
    private final ReentrantLock lock = new ReentrantLock();
    /** signaled by the provider when a resource has been delivered */
    private final Condition resourceProvided = lock.newCondition();
    /** signaled by the consumer when it is done with the resource */
    private final Condition resourceReleased = lock.newCondition();
    /** {@code true} while the consumer is blocked in {@link #waitForResource()} */
    private final AtomicBoolean waitingForResource = new AtomicBoolean(false);

    /**
     * Attempts to acquire the permit for this cycle. Only succeeds when the consumer is waiting for a resource.
     *
     * @return {@code true} if this provider acquired the permit
     */
    public boolean acquireProvidePermit() {
        if (!waitingForResource.get()) {
            return false;
        }
        return providePermit.tryAcquire();
    }

    /**
     * Attempts to acquire the permit regardless of whether the consumer is waiting. This effectively blocks all
     * other providers until {@link #releaseProvidePermit()} is called.
     *
     * @return {@code true} if this provider acquired the permit
     */
    public boolean tryBlockProvidePermit() {
        return providePermit.tryAcquire();
    }

    /**
     * Releases a previously acquired permit, allowing other providers to attempt to acquire it.
     */
    public void releaseProvidePermit() {
        providePermit.release();
    }

    /**
     * If the consumer {@link #isWaitingForResource()}, delivers a resource to the consumer.
     * Must only be called after acquiring the permit via
     * {@link #acquireProvidePermit()}. Blocks until the consumer closes the {@link LockedResource},
     * completing the cycle.
     *
     * @apiNote If a consumer waiting for this resource gets
     *   interrupted before signaling resourceReleased to the thread calling this method, it will get blocked indefinitely.
     * @param resource the resource to deliver
     * @throws InterruptedException if the provider thread is interrupted while waiting
     */
    public void provide(final T resource) throws InterruptedException {
        lock.lock();
        try {
            if (!isWaitingForResource()) {
                // In this case, the consumer for some reason (error or otherwise)
                // is not waiting anymore (it was when we got the permit), if we send the resource eitherway
                // the provider will become blocked as it will never get the notification back
                return;
            }
            this.resource.setResource(resource);
            // Signal the consumer that there is a resource available
            resourceProvided.signalAll();
            while (this.resource.getResource() != null) {
                // waiting for the resource to be used and closed by the consumer
                // It is responsibility of the consumer to signal even if it was interrupted
                // otherwise this provider will block indefinitely
                resourceReleased.await();
            }
        } finally {
            lock.unlock();
            // releases the permit to allow other providers
            providePermit.release();
        }
    }

    /**
     * Blocks the consumer until a provider delivers a resource. The returned {@link LockedResource} must be
     * closed when the consumer is done — this unblocks the provider and ends the cycle.
     *
     * @return a {@link LockedResource} holding the delivered resource; must be closed to complete the cycle
     * @throws InterruptedException if the consumer thread is interrupted while waiting.
     *   If a provider was able to deliver a resource just before getting interrupted, that resource will be lost.
     */
    public LockedResource<T> waitForResource() throws InterruptedException {
        lock.lock();
        waitingForResource.set(true);
        try {
            while (resource.getResource() == null) {
                resourceProvided.await();
            }
            return resource;
        } catch (final InterruptedException e) {
            // Makes sure that, in case the consumer was interrupted we close the resource to allow
            // the provider to gracefully continue its operations
            // The provided resource will no longer be available
            resource.close();
            throw e;
        } finally {
            waitingForResource.set(false);
        }
    }
    /**
     * Returns {@code true} if the consumer is currently blocked in {@link #waitForResource()}.
     */
    public boolean isWaitingForResource() {
        return waitingForResource.get();
    }

    private void consumerDone() {
        resource.setResource(null);
        resourceReleased.signalAll();
        lock.unlock();
    }
    /** the shared resource slot; closing it triggers {@link #consumerDone()} to end the cycle */
    private final LockedResource<T> resource = new AcquiredResource<>(this::consumerDone, null);
}
