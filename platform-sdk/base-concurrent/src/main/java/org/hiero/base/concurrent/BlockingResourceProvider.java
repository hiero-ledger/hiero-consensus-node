// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.hiero.base.concurrent.locks.internal.AcquiredResource;
import org.hiero.base.concurrent.locks.locked.LockedResource;

/**
 * A class that ensures thread safety where multiple providers compete to provide a resource to a single consumer
 * <ul>
 *     <li>The intended use case is multiple providers and a single consumer</li>
 *     <li>All providers should call {@link #acquireProvidePermit()} before providing the resource</li>
 *     <li>The permit will be unavailable until the consumer is blocked waiting for the resource</li>
 *     <li>Once the consumer is waiting, one provider will acquire the permit and can call {@link #provide(Object)}</li>
 *     <li>If the provider cannot provide the resource, it can release the permit for another provider to acquire</li>
 *     <li>Once the provider provides the resource, it will be blocked until the consumer is finished and releases
 *     it</li>
 *     <li>All other providers will not be able to acquire the permit until the process is finished and the consumer
 *     starts waiting again</li>
 * </ul>
 *
 * NOTE: Using this class in any way other than the way described can lead to unintended behaviour
 *
 * @param <T>
 * 		the type of resource provided
 */
public class BlockingResourceProvider<T> {
    /** tracks if the permit to provide is available or not */
    private final Semaphore providePermit = new Semaphore(1);
    /** lock used to synchronize the threads that provide and consume the resource */
    private final ReentrantLock lock = new ReentrantLock();
    /** the condition that synchs the point when the resource is provided */
    private final Condition resourceProvided = lock.newCondition();
    /** the condition that synchs the point when the resource is released by the consumer */
    private final Condition resourceReleased = lock.newCondition();
    /** is the consumer waiting for the resource */
    private final AtomicBoolean waitingForResource = new AtomicBoolean(false);

    /**
     * Try to acquire the provide permit
     *
     * @return true if the permit has been acquired
     */
    public boolean acquireProvidePermit() {
        if (!waitingForResource.get()) {
            return false;
        }
        return providePermit.tryAcquire();
    }

    /**
     * Try to acquire the provide permit bypassing the check to see if the consumer is waiting for the resource, this
     * will block the providers until {@link #releaseProvidePermit()} is called
     *
     * @return true if the permit has been acquired
     */
    public boolean tryBlockProvidePermit() {
        return providePermit.tryAcquire();
    }

    /**
     * Release a previously acquired provide permit
     */
    public void releaseProvidePermit() {
        providePermit.release();
    }

    /**
     * Provide the resource to the consumer. This method should only be called once the provide permit has been
     * acquired. This method will block until the consumer releases the resource.
     *
     * @param resource
     * 		the resource to provide
     * @throws InterruptedException
     * 		if the thread is interrupted while providing the resource
     */
    public void provide(final T resource) throws InterruptedException {
        lock.lock();
        try {
            this.resource.setResource(resource);
            resourceProvided.signalAll();
            while (this.resource.getResource() != null) {
                resourceReleased.await();
            }
        } finally {
            lock.unlock();
            providePermit.release();
        }
    }

    /**
     * Blocks until a resource is provided
     *
     * @return an {@link AutoCloseable} that releases the resource when closed
     * @throws InterruptedException
     * 		if interrupted while waiting for the resource
     */
    public LockedResource<T> waitForResource() throws InterruptedException {
        lock.lock();
        waitingForResource.set(true);
        try {
            while (resource.getResource() == null) {
                resourceProvided.await();
            }
            return resource;
        } finally {
            waitingForResource.set(false);
        }
    }

    private void consumerDone() {
        resource.setResource(null);
        resourceReleased.signalAll();
        lock.unlock();
    }
    /** an {@link AutoCloseable} that will provide the resource and signal the provider when closed */
    private final LockedResource<T> resource = new AcquiredResource<>(this::consumerDone, null);
}
