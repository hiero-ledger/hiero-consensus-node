// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.model.utility.StackTrace.getStackTrace;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A lightweight implementation of a {@link Future}.
 *
 * @param <T>
 * 		the object type returned by the Future
 */
public class StandardFuture<T> implements Future<T> {

    private static final Logger logger = LogManager.getLogger(StandardFuture.class);

    private final CountDownLatch latch;
    private final CompletionCallback<T> completionCallback;
    private final CancellationCallback cancellationCallback;
    private T value;
    private boolean cancelled;
    private Throwable exception;

    /**
     * A callback for when the future has been completed.
     *
     * @param <T>
     * 		the type of object held by the future
     */
    @FunctionalInterface
    public interface CompletionCallback<T> {
        void futureIsComplete(final T value);
    }

    /**
     * A callback for when the future is cancelled.
     */
    @FunctionalInterface
    public interface CancellationCallback {
        /**
         * This method is called when a future is cancelled.
         *
         * @param interrupt
         * 		if there is a thread computing the value of the future,
         * 		this specifies if that thread should be interrupted
         * @param exception
         * 		the exception that caused this future to be cancelled,
         * 		or null if this future was cancelled without an exception.
         */
        void futureIsCancelled(final boolean interrupt, final Throwable exception);
    }

    /**
     * Create a new {@link Future}.
     */
    public StandardFuture() {
        latch = new CountDownLatch(1);
        cancellationCallback = null;
        completionCallback = null;
    }

    /**
     * Create a new future that is instantly completed. More efficient than creating a future and then calling
     * complete if the completion value is already known.
     *
     * @param value
     * 		the computed result
     */
    public StandardFuture(final T value) {
        latch = null;
        cancellationCallback = null;
        completionCallback = null;
        this.value = value;
    }

    /**
     * Create a new {@link Future}.
     *
     * @param cancellationCallback
     * 		if not null, this method will be called if the future is cancelled
     */
    public StandardFuture(final CancellationCallback cancellationCallback) {
        this(null, cancellationCallback);
    }

    /**
     * Create a new {@link Future}.
     *
     * @param completionCallback
     * 		if not null, this method will be called if the future is completed
     */
    public StandardFuture(final CompletionCallback<T> completionCallback) {
        this(completionCallback, null);
    }

    /**
     * Create a new {@link Future}.
     *
     * @param completionCallback
     * 		if not null, this method will be called if the future is completed
     * @param cancellationCallback
     * 		if not null, this method will be called if the future is cancelled
     */
    public StandardFuture(
            final CompletionCallback<T> completionCallback, final CancellationCallback cancellationCallback) {

        this.latch = new CountDownLatch(1);
        this.cancellationCallback = cancellationCallback;
        this.completionCallback = completionCallback;
    }

    /**
     * Returns {@code true} if this task was cancelled before it completed normally.
     *
     * @return {@code true} if this task was cancelled before it completed
     */
    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns {@code true} if this task completed.
     * <p>
     * Completion may be due to normal termination, an exception, or cancellation -- in all of these cases, this method
     * will return {@code true}.
     *
     * @return {@code true} if this task completed
     */
    @Override
    public synchronized boolean isDone() {
        return latch == null || latch.getCount() == 0;
    }

    /**
     * Waits if necessary for the computation to complete, and then retrieves its result.
     *
     * @return the computed result
     * @throws CancellationException
     * 		if the computation was cancelled prior to completion
     * @throws InterruptedException
     * 		if the current thread was interrupted while waiting
     * @throws ExecutionException
     * 		if there was an exception on the thread computing the value
     */
    @Override
    public T get() throws InterruptedException, ExecutionException {
        if (latch != null) {
            // If there is a null latch, that means we are already done.
            latch.await();
        }

        if (cancelled) {
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            throw new CancellationException();
        }

        return value;
    }

    /**
     * Same functionality as {@link #get()}, but wraps any thrown {@link ExecutionException}s in a
     * {@link RuntimeException}.
     *
     * @return the computed result
     * @throws CancellationException
     * 		if the computation was cancelled prior to completion
     * @throws InterruptedException
     * 		if the current thread was interrupted while waiting
     */
    public T getAndRethrow() throws InterruptedException {
        try {
            return get();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Waits if necessary for at most the given time for the computation to complete, and then retrieves its result, if
     * available.
     *
     * @param timeout
     * 		the maximum time to wait
     * @param unit
     * 		the time unit of the timeout argument
     * @return the computed result
     * @throws CancellationException
     * 		if the computation was cancelled prior to completion
     * @throws InterruptedException
     * 		if the current thread was interrupted while waiting
     * @throws ExecutionException
     * 		if there was an exception on the thread computing the value
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public T get(final long timeout, final TimeUnit unit)
            throws ExecutionException, InterruptedException, TimeoutException {

        if (latch != null && !latch.await(timeout, unit)) {
            throw new TimeoutException();
        }

        if (cancelled) {
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            throw new CancellationException();
        }

        return value;
    }

    /**
     * Same functionality as {@link #get(long, TimeUnit)}, but wraps any thrown {@link ExecutionException}s in a
     * {@link RuntimeException}.
     *
     * @param timeout
     * 		the maximum time to wait
     * @param unit
     * 		the time unit of the timeout argument
     * @return the computed result
     * @throws CancellationException
     * 		if the computation was cancelled prior to completion
     * @throws InterruptedException
     * 		if the current thread was interrupted while waiting
     */
    public T getAndRethrow(final long timeout, final TimeUnit unit) throws InterruptedException, TimeoutException {
        try {
            return get(timeout, unit);
        } catch (final ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the raw value. Thread safety must be provided by calling context.
     *
     * @return the current value held by the future, may be null if future is not completed
     */
    protected T getRawValue() {
        return value;
    }

    /**
     * Helper method for signaling the completion of the future. Intended for internal use by the background process
     * completing the work. This method will have no effect if the future is already completed or cancelled.
     *
     * @param value
     * 		the computed result
     */
    public synchronized void complete(final T value) {
        if (isDone()) {
            if (cancelled) {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "Future has already been cancelled, can't complete " + "(provided value = {})\n{}",
                        value,
                        getStackTrace());
            } else {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "Future has already been completed can not complete again "
                                + "(current value = {}, provided value = {})\n{}",
                        this.value,
                        value,
                        getStackTrace());
            }
            return;
        }

        this.value = value;
        assert latch != null;
        latch.countDown();
        if (completionCallback != null) {
            completionCallback.futureIsComplete(value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean cancel(final boolean mayInterruptIfRunning) {
        if (isDone()) {
            if (cancelled) {
                logger.warn(EXCEPTION.getMarker(), "Future has already been cancelled\n{}", getStackTrace());
            } else {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "Future has already been completed, can not cancel " + "(current value = {})\n{}",
                        value,
                        getStackTrace());
            }
            return false;
        }

        cancelled = true;
        if (latch != null) {
            latch.countDown();
        }
        if (cancellationCallback != null) {
            cancellationCallback.futureIsCancelled(mayInterruptIfRunning, null);
        }

        return true;
    }

    /**
     * Helper method for signaling the cancellation of the future. Intended for internal use by the background process
     * cancelling the work. This method will have no effect if the future is already completed or cancelled.
     */
    public void cancel() {
        cancel(false);
    }

    /**
     * Helper method for signaling the cancellation of the future. Intended for internal use by the background process
     * cancelling the work.This method will have no effect if the future is already completed or cancelled.
     *
     * @param error
     * 		the error that caused the future to be cancelled
     */
    public void cancelWithError(final Throwable error) {
        cancelWithError(false, error);
    }

    /**
     * Helper method for signaling the cancellation of the future with an associated exception. Intended for internal
     * use by the background process cancelling the work. This method will have no effect if the future is already
     * completed or cancelled.
     *
     * @param mayInterruptIfRunning
     * 		if the thread that will complete this future should be interrupted
     * @param error
     * 		the error that caused the future to be cancelled
     */
    public synchronized void cancelWithError(final boolean mayInterruptIfRunning, final Throwable error) {
        if (isDone()) {
            if (cancelled) {
                logger.warn(EXCEPTION.getMarker(), "Future has already been cancelled\n{}", getStackTrace());
            } else {
                logger.warn(
                        EXCEPTION.getMarker(),
                        "Future has already been completed, can not cancel " + "(current value = {})\n{}",
                        value,
                        getStackTrace());
            }
            return;
        }

        if (cancellationCallback != null) {
            cancellationCallback.futureIsCancelled(mayInterruptIfRunning, error);
        }
        cancelled = true;
        exception = error;
        if (latch != null) {
            latch.countDown();
        }
    }
}
