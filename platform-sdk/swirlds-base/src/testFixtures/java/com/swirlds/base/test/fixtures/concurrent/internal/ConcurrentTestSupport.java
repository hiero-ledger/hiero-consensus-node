// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.concurrent.internal;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A utility class for executing and waiting for concurrent tasks using a thread pool.
 */
public class ConcurrentTestSupport implements TestExecutor, AutoCloseable {

    private static final String NAME_PREFIX = ConcurrentTestSupport.class.getSimpleName();
    private static final AtomicInteger ID = new AtomicInteger(0);

    private final Duration maxWaitTime;
    private final ExecutorService executorService;

    /**
     * Constructs a ConcurrentTestSupport instance with the specified maximum wait time.
     *
     * @param maxWaitTime The maximum time to wait for task completion. Must not be null.
     */
    public ConcurrentTestSupport(@NonNull final Duration maxWaitTime) {
        this.maxWaitTime = Objects.requireNonNull(maxWaitTime, "maxWaitTime must not be null");
        this.executorService = Executors.newCachedThreadPool(r -> {
            final Thread thread = new Thread(r);
            thread.setName(NAME_PREFIX + "-pool-" + this.hashCode() + ID.getAndDecrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Constructs a ConcurrentTestSupport instance with a default maximum wait time of 1 minute.
     */
    public ConcurrentTestSupport() {
        this(Duration.ofMinutes(1));
    }

    /**
     * Executes a collection of {@code Runnable} concurrently and waits for their completion.
     *
     * @param runnables The collection of {@code Runnable} to execute.
     */
    public void executeAndWait(@NonNull final Collection<Runnable> runnables) {
        Objects.requireNonNull(runnables, "runnables must not be null");
        try {
            CompletableFuture.allOf(runnables.stream()
                            .map(r -> CompletableFuture.runAsync(r, executorService))
                            .toArray(CompletableFuture[]::new))
                    .get(maxWaitTime.toMillis(), MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e.getClass().getSimpleName() + " in submitAndWait", e);
        }
    }

    /**
     * Executes an array of Runnables concurrently and waits for their completion.
     *
     * @param runnables An array of Runnables to execute.
     * @see ConcurrentTestSupport#executeAndWait(Collection)
     */
    public void executeAndWait(@NonNull final Runnable... runnables) {
        executeAndWait(List.of(Objects.requireNonNull(runnables, "runnables must not be null")));
    }

    /**
     * Submits a collection of Callables for execution concurrently and waits for their results.
     *
     * @param callables The collection of Callables to submit.
     * @param <V>       The type of the results returned by the Callables.
     * @return A list of results from the executed Callables. The order of the elements in the list is not guaranteed
     * @see ConcurrentTestSupport#executeAndWait(Collection)
     */
    @NonNull
    public <V> List<V> submitAndWait(@NonNull final Collection<Callable<V>> callables) {
        Objects.requireNonNull(callables, "callables must not be null");
        final Deque<V> result = callables.size() > 1 ? new ConcurrentLinkedDeque<>() : new ArrayDeque<>();
        executeAndWait(callablesToRunners(callables, result));
        return List.copyOf(result);
    }

    /**
     * Submits a Callable for execution concurrently and waits for its results.
     *
     * @param callable A callable to submit.
     * @param <V>      The type of the results returned by the Callables.
     * @return result from the executed Callables. It can be null.
     * @see ConcurrentTestSupport#executeAndWait(Runnable...)
     */
    @NonNull
    @SafeVarargs
    public final <V> List<V> submitAndWait(@NonNull final Callable<V>... callable) {
        Objects.requireNonNull(callable, "callable must not be null");
        return submitAndWait(List.of(callable));
    }

    /**
     * Closes this the underlying executorService so all pending activity is finished before finishing the test
     */
    @Override
    public void close() {
        executorService.close();
    }

    // Return a collections of runners from a collection of callables.
    // Results are accumulated  in result
    private static <V> Collection<Runnable> callablesToRunners(
            final Collection<Callable<V>> callables, final Collection<V> result) {
        return callables.stream().map(c -> toRunnable(c, result)).collect(Collectors.toList());
    }

    // Return a runners from a callable.
    // The result is accumulated  in result
    private static <V> Runnable toRunnable(final Callable<V> callable, final Collection<V> result) {
        return () -> {
            try {
                result.add(callable.call());
            } catch (Exception e) {
                throw new RuntimeException("Error in submitAndWait", e);
            }
        };
    }
}
