// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.pool;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.futures.ConcurrentFuturePool;

/**
 * A group of {@link Thread}s designed to support the following paradigm:
 *
 * 1) One or more threads are created to perform a task.
 * 2) Zero or more threads are created by those threads (or other descendant threads) to assist with the task.
 * 3) When the task is finished, all worker threads terminate.
 * 4) If any worker thread throws an exception, all threads stop and the exception is delivered to the calling context.
 */
public class StandardWorkGroup {

    private static final Logger logger = LogManager.getLogger(StandardWorkGroup.class);

    private static final String DEFAULT_TASK_NAME = "IDLE";

    private final String groupName;
    private final boolean logExceptionsToStdErr;
    private final ExecutorService executorService;

    private final ConcurrentFuturePool<Void> futures;

    private volatile boolean hasExceptions;

    private final AtomicBoolean firstException = new AtomicBoolean(true);
    private final Runnable abortAction;
    private final Function<Throwable, Boolean> exceptionListener;

    public StandardWorkGroup(final ThreadManager threadManager, final String groupName, final Runnable abortAction) {
        this(threadManager, groupName, abortAction, null, false);
    }

    /**
     * Create a new work group.
     *
     * @param threadManager
     * 		responsible for managing thread lifecycle
     * @param groupName
     * 		the name of the group
     * @param abortAction
     * 		if a non-<code>InterruptedException</code> exception is encountered, execute this method.
     * 		All threads in the work group are interrupted, but
     * 		if there is additional cleanup required then this method
     * 		can be used to perform that cleanup. Method is called at most
     * 		one time. If argument is null then no additional action is taken.
     * @param exceptionListener
     *      If a non-<code>InterruptedException</code> exception is encountered, it is passed to this
     *      listener. If the listener returns {@code true}, the exception is considered handled, and
     *      no further action is performed. If the listener returns {@code false}, it indicates the
     *      exception should be processed by the default handler, which is to log it appropriately
     */
    public StandardWorkGroup(
            final ThreadManager threadManager,
            final String groupName,
            final Runnable abortAction,
            final Function<Throwable, Boolean> exceptionListener) {
        this(threadManager, groupName, abortAction, exceptionListener, false);
    }

    public StandardWorkGroup(
            final ThreadManager threadManager,
            final String groupName,
            final Runnable abortAction,
            final Function<Throwable, Boolean> exceptionListener,
            final boolean logExceptionsToStdErr) {
        this.groupName = groupName;
        this.logExceptionsToStdErr = logExceptionsToStdErr;
        this.futures = new ConcurrentFuturePool<>(this::handleError);

        this.abortAction = abortAction;
        this.exceptionListener = exceptionListener;

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
     * @param operation
     * 		the method to run on the thread
     */
    @SuppressWarnings("unchecked")
    public void execute(final Runnable operation) {
        futures.add((Future<Void>) executorService.submit(operation));
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
    public void execute(final String taskName, final Runnable operation) {
        final Runnable wrapper = () -> {
            final String originalThreadName = Thread.currentThread().getName();
            final String newThreadName = originalThreadName.replaceFirst(DEFAULT_TASK_NAME, taskName);

            try {
                Thread.currentThread().setName(newThreadName);
                operation.run();
            } finally {
                Thread.currentThread().setName(originalThreadName);
            }
        };

        execute(wrapper);
    }

    public boolean hasExceptions() {
        return hasExceptions;
    }

    public void waitForTermination() throws InterruptedException {
        futures.waitForCompletion();
        executorService.shutdown();

        while (!executorService.isTerminated()) {
            if (executorService.awaitTermination(10, TimeUnit.MILLISECONDS)) {
                break;
            }
        }
    }

    /**
     * Pass an exception to the work group for handling. Will cause the work group to be torn down.
     *
     * @param ex
     * 		an exception
     */
    public void handleError(final Throwable ex) {
        if (!(ex instanceof InterruptedException)) {
            boolean exceptionHandled = false;
            if (exceptionListener != null) {
                exceptionHandled = exceptionListener.apply(ex);
            }
            if (!exceptionHandled) {
                logger.error(EXCEPTION.getMarker(), "Work Group Exception [ groupName = {} ]", groupName, ex);
                // Log to stderr for testing purposes
                if (logExceptionsToStdErr) {
                    ex.printStackTrace(System.err);
                }
            }

            hasExceptions = true;
            if (abortAction != null && firstException.getAndSet(false)) {
                abortAction.run();
            }

            executorService.shutdownNow();
        }
    }
}
