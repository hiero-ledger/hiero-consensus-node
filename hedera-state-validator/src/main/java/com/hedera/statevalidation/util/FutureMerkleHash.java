// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hiero.base.crypto.Hash;

/**
 * This future object is used to represent the eventual hash of a Merkle tree.
 */
public class FutureMerkleHash implements Future<Hash> {

    private volatile Hash hash;
    private volatile Throwable exception;
    private final CountDownLatch latch;

    /**
     * Create a future that will eventually have the hash specified.
     */
    public FutureMerkleHash() {
        latch = new CountDownLatch(1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
    }

    /**
     * This method is used to register that an exception was encountered while hashing the tree.
     */
    public synchronized void cancelWithException(@NonNull final Throwable t) {
        if (exception == null) {
            // Only the first exception gets rethrown
            exception = t;
            latch.countDown();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return exception != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        return hash != null;
    }

    /**
     * If there were any exceptions encountered during hashing, rethrow that exception.
     */
    private void rethrowException() throws ExecutionException {
        if (exception != null) {
            throw new ExecutionException(exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash get() throws InterruptedException, ExecutionException {
        latch.await();
        rethrowException();
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash get(final long timeout, final TimeUnit unit)
            throws InterruptedException, TimeoutException, ExecutionException {
        if (!latch.await(timeout, unit)) {
            throw new TimeoutException();
        }
        rethrowException();
        return hash;
    }

    /**
     * Set the hash for the tree.
     *
     * @param hash
     * 		the hash
     */
    public synchronized void set(Hash hash) {
        if (exception == null) {
            this.hash = hash;
            latch.countDown();
        }
    }
}
