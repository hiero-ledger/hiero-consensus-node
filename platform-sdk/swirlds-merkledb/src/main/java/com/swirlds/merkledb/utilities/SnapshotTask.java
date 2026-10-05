// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.utilities;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.io.IORunnable;

/// Runs a snapshot operation unless another operation has already failed.
public final class SnapshotTask extends AbstractTask {

    private final IORunnable operation;
    private final AtomicReference<Throwable> failure;
    private final CompletableFuture<Void> result = new CompletableFuture<>();

    private SnapshotTask(
            final ForkJoinPool pool, final AtomicReference<Throwable> failure, final IORunnable operation) {
        super(pool, 1);
        this.operation = operation;
        this.failure = failure;
    }

    /// Submits an operation and reports failure only after that operation has stopped.
    /// The shared failure marker lets queued operations skip work after a snapshot fails.
    public static CompletableFuture<Void> submit(
            final ForkJoinPool pool, final AtomicReference<Throwable> failure, final IORunnable operation) {
        final SnapshotTask task = new SnapshotTask(pool, failure, operation);
        try {
            task.send();
        } catch (final RuntimeException | Error e) {
            task.onException(e);
        }
        return task.result;
    }

    /// Waits for every operation to stop, then reports the shared failure with its suppressed errors.
    public static CompletableFuture<Void> allOf(
            final AtomicReference<Throwable> failure, final CompletableFuture<?>... operations) {
        return CompletableFuture.allOf(operations).handle((_, exception) -> {
            if (failure.get() != null) {
                throw new CompletionException(failure.get());
            }
            if (exception != null) {
                throw new CompletionException(exception);
            }
            return null;
        });
    }

    @Override
    protected boolean onExecute() throws IOException {
        final Throwable previousFailure = failure.get();
        if (previousFailure == null) {
            operation.run();
            result.complete(null);
        } else {
            result.completeExceptionally(previousFailure);
        }
        return true;
    }

    @Override
    protected void onException(final Throwable t) {
        recordFailure(failure, t);
        result.completeExceptionally(t);
    }

    /// Keeps the original failure and any later errors from running tasks or file cleanup.
    /// A rejected submission remains the primary error if an accepted writer also fails.
    public static void recordFailure(final AtomicReference<Throwable> failure, final Throwable t) {
        synchronized (failure) {
            final Throwable previousFailure = failure.get();
            if (previousFailure == null) {
                failure.set(t);
            } else if (previousFailure != t) {
                if (t instanceof RejectedExecutionException
                        && !(previousFailure instanceof RejectedExecutionException)) {
                    t.addSuppressed(previousFailure);
                    failure.set(t);
                } else {
                    previousFailure.addSuppressed(t);
                }
            }
        }
    }
}
