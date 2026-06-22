// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.pool;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.manager.ThreadManager;

/**
 * A logic group for parallel tasks execution using {@link ExecutorService} where
 * tasks can be forked by {@link #fork(Runnable)} or {@link #fork(String, Runnable)}.
 * <br>
 * Use {@link #join()} to wait until all tasks are completed.
 * <br>
 * If any task throws an exception, all other will be interrupted and exception will be rethrown by {@link #join()}.
 *
 * <p>The API mirrors {@code StructuredTaskScope} with {@code Joiner.awaitAllSuccessfulOrThrow()} preview feature from JDK structured concurrency.
 */
// Future Work:  migrate to StructuredTaskScope when it is available in a stable JDK release, not as preview feature -
// https://github.com/hiero-ledger/hiero-consensus-node/issues/25696
public class StandardWorkGroup implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(StandardWorkGroup.class);

    private static final String DEFAULT_TASK_NAME = "IDLE";

    private final String groupName;
    private final boolean logExceptionsToStdErr;
    private final ExecutorService executorService;

    @Nullable
    private final Runnable abortAction;

    private final ConcurrentLinkedQueue<Future<?>> futures = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> firstException = new AtomicReference<>(null);

    /**
     * Create a new work group without abort action and logging exceptions.
     *
     * <p>See {@link #StandardWorkGroup(ThreadManager, String, Runnable, boolean)}
     */
    public StandardWorkGroup(final ThreadManager threadManager, final String groupName) {
        this(threadManager, groupName, null, false);
    }

    /**
     * Create a new work group without logging exceptions.
     *
     * <p>See {@link #StandardWorkGroup(ThreadManager, String, Runnable, boolean)}
     */
    public StandardWorkGroup(
            final ThreadManager threadManager, final String groupName, @Nullable final Runnable abortAction) {
        this(threadManager, groupName, abortAction, false);
    }

    /**
     * Create a new work group.
     *
     * @param threadManager
     * 		responsible for managing thread lifecycle
     * @param groupName
     * 		the name of the group, used for logging and debugging purposes
     * @param abortAction
     *      called exactly once when the first non-{@link InterruptedException} task exception is
     *      recorded, before worker threads are interrupted via {@code shutdownNow()}. Use this to
     *      release external resources (e.g. close network sockets) that blocking I/O threads may
     *      be waiting on and that do not respond to {@link Thread#interrupt()}. May be {@code null}.
     * @param logExceptionsToStdErr whether to log all tasks exceptions to {@link System#err}.
     */
    public StandardWorkGroup(
            final ThreadManager threadManager,
            final String groupName,
            @Nullable final Runnable abortAction,
            final boolean logExceptionsToStdErr) {
        this.groupName = groupName;
        this.logExceptionsToStdErr = logExceptionsToStdErr;
        this.abortAction = abortAction;

        final ThreadConfiguration configuration = new ThreadConfiguration(threadManager)
                .setComponent("work group " + groupName)
                .setExceptionHandler((t, ex) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception ", ex))
                .setThreadName(DEFAULT_TASK_NAME);

        this.executorService = Executors.newCachedThreadPool(configuration.buildFactory());
    }

    public void shutdown() {
        executorService.shutdown();
    }

    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    public boolean isTerminated() {
        return executorService.isTerminated();
    }

    /**
     * Perform an action on a thread managed by the work group. Any uncaught exception
     * (excluding {@link InterruptedException}) will be caught by the work group and will result
     * in the termination of all threads in the work group.
     *
     * <p>Analogous to {@code StructuredTaskScope.fork()}.
     *
     * @param operation
     * 		the method to run on the thread
     */
    public void fork(final Runnable operation) {
        fork(null, operation);
    }

    /**
     * Perform an action on a thread managed by the work group. Any uncaught exception
     * (excluding {@link InterruptedException}) will be caught by the work group and will result
     * in the termination of all threads in the work group.
     *
     * @param taskName
     * 		used when naming the thread used by the work group
     * @param operation
     * 		the method to run on the thread
     */
    public void fork(final String taskName, final Runnable operation) {
        try {
            futures.add(executorService.submit(wrap(taskName, operation)));
        } catch (final RuntimeException e) {
            handleError(e);
            throw e;
        }
    }

    private Runnable wrap(final String taskName, final Runnable operation) {
        if (taskName == null) {
            return () -> {
                try {
                    operation.run();
                } catch (final Throwable e) {
                    handleError(e);
                }
            };
        } else {
            return () -> {
                final String originalThreadName = Thread.currentThread().getName();
                final String newThreadName = originalThreadName.replaceFirst(DEFAULT_TASK_NAME, taskName);
                Thread.currentThread().setName(newThreadName);

                try {
                    operation.run();
                } catch (final Throwable e) {
                    handleError(e);
                } finally {
                    Thread.currentThread().setName(originalThreadName);
                }
            };
        }
    }

    /**
     * Waits for all submitted tasks to complete, then surfaces the first task exception if any.
     *
     * <p>Analogous to {@code StructuredTaskScope.join()} followed by {@code throwIfFailed()}:
     * blocks until every forked task is done, then throws the first captured task exception.
     * If the calling thread is interrupted, all running tasks are cancelled immediately via
     * {@code shutdownNow()}, this method doesn't wait for every task to finish,
     * but just throws {@link InterruptedException} (taking priority over any
     * task exception), expecting this group will be anyway closed by {@link #close()}.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting;
     *         all running tasks will have been interrupted before this is thrown
     * @throws ParallelExecutionException wrapping the first exception thrown by any task
     */
    public void join() throws InterruptedException, ParallelExecutionException {
        Future<?> future;
        while ((future = futures.poll()) != null) {
            try {
                future.get();
            } catch (final InterruptedException e) {
                // Cancel all running tasks, then propagate immediately without waiting;
                // close() will ensure every task has terminated.
                executorService.shutdownNow();
                throw e;
            } catch (final ExecutionException | CancellationException e) {
                break; // defensive — wrapped operation does not re-throw, so this should not occur
            }
        }

        final Throwable throwable = firstException.get();
        if (throwable != null) {
            throw new ParallelExecutionException(throwable);
        }
    }

    /**
     * Shuts down the work group and waits for all tasks to finish.
     *
     * <p>Analogous to {@code StructuredTaskScope.close()}: interrupts all running tasks via
     * {@code shutdownNow()}, then blocks until every task has terminated. Does not throw task
     * exceptions — intended as the {@code AutoCloseable} guard in a try-with-resources block to
     * ensure cleanup even when {@link #join()} throws. If interrupted
     * while waiting, the interrupt flag is restored on return.
     */
    @Override
    public void close() {
        executorService.shutdownNow();

        boolean interrupted = false;
        while (!executorService.isTerminated()) {
            try {
                executorService.awaitTermination(10, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pass an exception to the work group for handling. Will cause the work group to be torn down.
     *
     * @param ex
     * 		an exception
     */
    private void handleError(final Throwable ex) {
        if (!(ex instanceof InterruptedException)) {
            if (firstException.compareAndSet(null, ex)) {
                logger.error(EXCEPTION.getMarker(), "Work Group Exception [ groupName = {} ]", groupName, ex);
                if (abortAction != null) {
                    try {
                        abortAction.run();
                    } catch (final Exception abortEx) {
                        logger.warn(
                                EXCEPTION.getMarker(),
                                "Work Group abort action failed [ groupName = {} ]",
                                groupName,
                                abortEx);
                    }
                }
                executorService.shutdownNow();
            } else {
                logger.warn(EXCEPTION.getMarker(), "Work Group Exception [ groupName = {} ]:", groupName, ex);
            }
            if (logExceptionsToStdErr) {
                ex.printStackTrace(System.err);
            }
        }
    }
}
