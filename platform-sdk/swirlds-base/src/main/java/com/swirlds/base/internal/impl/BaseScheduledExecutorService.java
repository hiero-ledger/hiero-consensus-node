// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal.impl;

import com.swirlds.base.internal.observe.BaseExecutorObserver;
import com.swirlds.base.internal.observe.BaseTaskDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A scheduled executor service for the base modules. The executor is based on a single thread and is a daemon thread
 * with a low priority.
 * <p>
 * This class is a singleton and should only be used by code in the base modules that highly needs an asynchronous
 * executor.
 */
public class BaseScheduledExecutorService implements ScheduledExecutorService {

    /**
     * The number of threads in the pool.
     */
    public static final int CORE_POOL_SIZE = 1;

    private static final class InstanceHolder {
        private static final BaseScheduledExecutorService INSTANCE = new BaseScheduledExecutorService();
    }

    /**
     * The lock for creating the singleton instance.
     */
    private static final Lock instanceLock = new ReentrantLock();

    /**
     * The inner executor service.
     */
    private final ScheduledExecutorService innerService;

    /*
     * The observers of this executor.
     */
    private final List<BaseExecutorObserver> observers;

    /**
     * Constructs a new executor.
     */
    private BaseScheduledExecutorService() {
        final ThreadFactory threadFactory = new BaseExecutorThreadFactory();
        this.innerService = Executors.newScheduledThreadPool(CORE_POOL_SIZE, threadFactory);
        this.observers = new CopyOnWriteArrayList<>();
        final Thread shutdownHook = new Thread(() -> innerService.shutdown());
        shutdownHook.setName("BaseScheduledExecutorService-shutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Adds an observer to this executor.
     *
     * @param observer the observer to add
     */
    public void addObserver(@NonNull final BaseExecutorObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        observers.add(observer);
    }

    /**
     * Removes an observer from this executor.
     * @param observer the observer to remove
     */
    public void removeObserver(@NonNull final BaseExecutorObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        observers.add(observer);
    }

    /**
     * Wraps the given command with the observer calls.
     * @param command the command to wrap
     * @return the wrapped command
     */
    @NonNull
    private Runnable wrapOnSubmit(@NonNull final Runnable command) {
        Objects.requireNonNull(command, "command must not be null");
        final BaseTaskDefinition taskDefinition = BaseTaskDefinition.of(command);
        observers.forEach(observer -> observer.onTaskSubmitted(taskDefinition));
        return () -> {
            final long start = System.currentTimeMillis();
            observers.forEach(observer -> observer.onTaskStarted(taskDefinition));
            try {
                command.run();
                observers.forEach(observer ->
                        observer.onTaskDone(taskDefinition, Duration.ofMillis(System.currentTimeMillis() - start)));
            } catch (Throwable t) {
                observers.forEach(observer ->
                        observer.onTaskFailed(taskDefinition, Duration.ofMillis(System.currentTimeMillis() - start)));
                throw t;
            }
        };
    }

    /**
     * Wraps the given callable with the observer calls.
     * @param callable the callable to wrap
     * @return the wrapped callable
     * @param <V> the type of the callable's result
     */
    @NonNull
    private <V> Callable<V> wrapOnSubmit(@NonNull final Callable<V> callable) {
        Objects.requireNonNull(callable, "callable must not be null");
        final BaseTaskDefinition taskDefinition = BaseTaskDefinition.of(callable);
        observers.forEach(observer -> observer.onTaskSubmitted(taskDefinition));
        return () -> {
            final long start = System.currentTimeMillis();
            observers.forEach(observer -> observer.onTaskStarted(taskDefinition));
            try {
                final V result = callable.call();
                observers.forEach(observer ->
                        observer.onTaskDone(taskDefinition, Duration.ofMillis(System.currentTimeMillis() - start)));
                return result;
            } catch (Throwable t) {
                observers.forEach(observer ->
                        observer.onTaskFailed(taskDefinition, Duration.ofMillis(System.currentTimeMillis() - start)));
                throw t;
            }
        };
    }

    @Override
    public ScheduledFuture<?> schedule(
            @NonNull final Runnable command, final long delay, @NonNull final TimeUnit unit) {
        final Runnable wrapped = wrapOnSubmit(command);
        return innerService.schedule(wrapped, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(
            @NonNull final Callable<V> callable, final long delay, @NonNull final TimeUnit unit) {
        final Callable<V> wrapped = wrapOnSubmit(callable);
        return innerService.schedule(wrapped, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            @NonNull final Runnable command, final long initialDelay, final long period, @NonNull final TimeUnit unit) {
        final Runnable wrapped = wrapOnSubmit(command);
        return innerService.scheduleAtFixedRate(wrapped, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            @NonNull final Runnable command, final long initialDelay, final long delay, @NonNull final TimeUnit unit) {
        final Runnable wrapped = wrapOnSubmit(command);
        return innerService.scheduleWithFixedDelay(wrapped, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        throw new IllegalStateException("This executor is managed by the base modules and should not be shut down");
    }

    @Override
    public List<Runnable> shutdownNow() {
        throw new IllegalStateException("This executor is managed by the base modules and should not be shut down");
    }

    @Override
    public boolean isShutdown() {
        return innerService.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return innerService.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return innerService.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        final Callable<T> wrapped = wrapOnSubmit(task);
        return innerService.submit(wrapped);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        final Runnable wrapped = wrapOnSubmit(task);
        return innerService.submit(wrapped, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        final Runnable wrapped = wrapOnSubmit(task);
        return innerService.submit(wrapped);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        Collection<? extends Callable<T>> wrapped =
                tasks.stream().map(this::wrapOnSubmit).toList();
        return innerService.invokeAll(wrapped);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        Collection<? extends Callable<T>> wrapped =
                tasks.stream().map(this::wrapOnSubmit).toList();
        return innerService.invokeAll(wrapped, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        Collection<? extends Callable<T>> wrapped =
                tasks.stream().map(this::wrapOnSubmit).toList();
        return innerService.invokeAny(wrapped);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Collection<? extends Callable<T>> wrapped =
                tasks.stream().map(this::wrapOnSubmit).toList();
        return innerService.invokeAny(wrapped, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        final Runnable wrapped = wrapOnSubmit(command);
        innerService.execute(wrapped);
    }

    /**
     * Returns the singleton instance of this executor.
     *
     * @return the instance
     */
    @NonNull
    public static BaseScheduledExecutorService getInstance() {
        return InstanceHolder.INSTANCE;
    }
}
