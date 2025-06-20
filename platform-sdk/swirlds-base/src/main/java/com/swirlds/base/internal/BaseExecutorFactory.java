// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal;

import com.swirlds.base.internal.impl.BaseExecutorFactoryImpl;
import com.swirlds.base.internal.impl.BaseScheduledExecutorService;
import com.swirlds.base.internal.observe.BaseExecutorObserver;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This factory creates / provides executors for the base modules. The factory should only be used by code in the base
 * modules that highly needs an asynchronous executor. All executors that are created by this factory are daemon
 * threads and have a low priority.
 */
public interface BaseExecutorFactory {

    /**
     * Returns a {@link ScheduledExecutorService} that is based on a single thread.
     * Calling the method several times will always return the same instance.
     * @return the executor
     */
    @NonNull
    ScheduledExecutorService getScheduledExecutor();

    /**
     * Submits a value-returning task for execution and returns a Future representing the pending results of the task.
     *
     * @param task the task to submit
     * @return a Future representing pending completion of the task
     * @see java.util.concurrent.ExecutorService#submit(Runnable)
     */
    @NonNull
    default Future<Void> submit(@NonNull final Runnable task) {
        return getScheduledExecutor().submit(task, null);
    }

    /**
     * Submits a value-returning task for execution and returns a Future representing the pending results of the task.
     *
     * @param task the task to submit
     * @param <V>  the type of the task's result
     * @return a Future representing pending completion of the task
     * @see java.util.concurrent.ExecutorService#submit(Callable)
     */
    @NonNull
    default <V> Future<V> submit(@NonNull final Callable<V> task) {
        return getScheduledExecutor().submit(task);
    }

    /**
     * Creates and executes a periodic action that becomes enabled first after the given initial delay, and subsequently
     * with the given period.
     *
     * @param task         the task to execute
     * @param initialDelay the time to delay first execution
     * @param period       the period between successive executions
     * @param unit         the time unit of the initialDelay and period parameters
     * @return a ScheduledFuture representing pending completion of the series of repeated tasks.
     * @see java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate(Runnable, long, long, TimeUnit)
     */
    @NonNull
    default ScheduledFuture<Void> scheduleAtFixedRate(
            @NonNull final Runnable task, long initialDelay, long period, @NonNull final TimeUnit unit) {
        final ScheduledFuture<?> scheduledFuture =
                getScheduledExecutor().scheduleAtFixedRate(task, initialDelay, period, unit);
        return wrap(scheduledFuture);
    }

    /**
     * Creates and executes a one-shot action that becomes enabled after the given delay.
     *
     * @param command the task to execute
     * @param delay   the time from now to delay execution
     * @param unit    the time unit of the delay parameter
     * @return a ScheduledFuture representing pending completion of the task.
     */
    @NonNull
    default ScheduledFuture<Void> schedule(
            @NonNull final Runnable command, final long delay, @NonNull final TimeUnit unit) {
        final ScheduledFuture<?> scheduledFuture = getScheduledExecutor().schedule(command, delay, unit);
        return wrap(scheduledFuture);
    }

    @NonNull
    private static ScheduledFuture<Void> wrap(@NonNull final ScheduledFuture<?> scheduledFuture) {
        return new ScheduledFuture<Void>() {
            @Override
            public long getDelay(TimeUnit unit) {
                return scheduledFuture.getDelay(unit);
            }

            @Override
            public int compareTo(Delayed o) {
                return scheduledFuture.compareTo(o);
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return scheduledFuture.cancel(mayInterruptIfRunning);
            }

            @Override
            public boolean isCancelled() {
                return scheduledFuture.isCancelled();
            }

            @Override
            public boolean isDone() {
                return scheduledFuture.isDone();
            }

            @Override
            public Void get() throws InterruptedException, ExecutionException {
                scheduledFuture.get();
                return null;
            }

            @Override
            public Void get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                scheduledFuture.get(timeout, unit);
                return null;
            }
        };
    }

    /**
     * Returns the singleton instance of this factory.
     *
     * @return the instance
     */
    @NonNull
    static BaseExecutorFactory getInstance() {
        return BaseExecutorFactoryImpl.getInstance();
    }

    /**
     * Adds an observer to the executor.
     *
     * @param observer the observer to add
     */
    static void addObserver(@NonNull final BaseExecutorObserver observer) {
        BaseScheduledExecutorService.getInstance().addObserver(observer);
    }

    /**
     * Removes an observer from the executor.
     *
     * @param observer the observer to remove
     */
    static void removeObserver(@NonNull final BaseExecutorObserver observer) {
        BaseScheduledExecutorService.getInstance().removeObserver(observer);
    }
}
