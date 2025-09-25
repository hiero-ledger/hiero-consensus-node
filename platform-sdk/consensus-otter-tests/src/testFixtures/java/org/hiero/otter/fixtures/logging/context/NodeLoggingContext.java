// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.context;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.ThreadContext;

/**
 * Utility methods for propagating the Log4j2 {@link ThreadContext} (MDC) across asynchronous
 * execution boundaries. All helpers capture the current context at invocation time and reapply it
 * when the wrapped task executes.
 */
public final class NodeLoggingContext {

    /** ThreadContext key that stores the node identifier. */
    public static final String NODE_ID_KEY = "nodeId";

    private NodeLoggingContext() {
        // utility
    }

    /**
     * Installs the supplied {@code nodeId} into the current {@link ThreadContext}. The returned
     * scope restores the previous context on {@link AutoCloseable#close()}.
     *
     * @param nodeId the identifier to associate with this thread
     * @return a scope that restores the previous context on close
     */
    @NonNull
    public static LoggingContextScope install(@NonNull final String nodeId) {
        requireNonNull(nodeId, "nodeId must not be null");
        final ContextSnapshot previous = snapshot();
        ThreadContext.put(NODE_ID_KEY, nodeId);
        return new ContextScope(previous);
    }

    /**
     * Wraps the supplied {@link Runnable}, capturing the current {@link ThreadContext}. The wrapped
     * runnable restores the original context after execution to avoid leaking context data across
     * pooled worker threads.
     *
     * @param runnable the runnable to wrap
     * @return a Runnable that propagates the captured context
     */
    @NonNull
    public static Runnable wrap(@NonNull final Runnable runnable) {
        requireNonNull(runnable, "runnable must not be null");
        final ContextSnapshot snapshot = snapshot();
        return () -> runWithSnapshot(snapshot, runnable);
    }

    /**
     * Wraps the supplied {@link Callable}, capturing the current {@link ThreadContext}. The wrapped
     * callable restores the original context after execution to avoid leakage across pooled worker
     * threads.
     *
     * @param callable the callable to wrap
     * @param <T>      the callable return type
     * @return a Callable that propagates the captured context
     */
    @NonNull
    public static <T> Callable<T> wrap(@NonNull final Callable<T> callable) {
        requireNonNull(callable, "callable must not be null");
        final ContextSnapshot snapshot = snapshot();
        return () -> callWithSnapshot(snapshot, callable);
    }

    /**
     * Returns a delegating {@link ExecutorService} that propagates the submitting thread's
     * {@link ThreadContext} to the worker executing the task.
     *
     * @param delegate the executor to wrap
     * @return a context-propagating executor service
     */
    @NonNull
    public static ExecutorService wrap(@NonNull final ExecutorService delegate) {
        requireNonNull(delegate, "delegate must not be null");
        if (delegate instanceof ContextPropagatingExecutorService) {
            return delegate;
        }
        return new ContextPropagatingExecutorService(delegate);
    }

    /**
     * Returns a delegating {@link ScheduledExecutorService} that propagates the submitting thread's
     * {@link ThreadContext} to every scheduled task execution.
     *
     * @param delegate the scheduler to wrap
     * @return a context-propagating scheduled executor service
     */
    @NonNull
    public static ScheduledExecutorService wrap(@NonNull final ScheduledExecutorService delegate) {
        requireNonNull(delegate, "delegate must not be null");
        if (delegate instanceof ContextPropagatingScheduledExecutorService) {
            return delegate;
        }
        return new ContextPropagatingScheduledExecutorService(delegate);
    }

    static void runWithSnapshot(@NonNull final ContextSnapshot snapshot, @NonNull final Runnable runnable) {
        final ContextSnapshot previous = snapshot();
        apply(snapshot);
        try {
            runnable.run();
        } finally {
            restore(previous);
        }
    }

    private static <T> T callWithSnapshot(@NonNull final ContextSnapshot snapshot, @NonNull final Callable<T> callable)
            throws Exception {
        final ContextSnapshot previous = snapshot();
        apply(snapshot);
        try {
            return callable.call();
        } finally {
            restore(previous);
        }
    }

    @NonNull
    static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                new HashMap<>(ThreadContext.getImmutableContext()),
                new ArrayList<>(ThreadContext.getImmutableStack().asList()));
    }

    private static void apply(@NonNull final ContextSnapshot snapshot) {
        ThreadContext.clearMap();
        if (!snapshot.map().isEmpty()) {
            ThreadContext.putAll(snapshot.map());
        }
        ThreadContext.clearStack();
        final List<String> stack = snapshot.stack();
        for (int i = stack.size() - 1; i >= 0; i--) {
            ThreadContext.push(stack.get(i));
        }
    }

    private static void restore(@NonNull final ContextSnapshot snapshot) {
        apply(snapshot);
    }

    record ContextSnapshot(@NonNull Map<String, String> map, @NonNull List<String> stack) {}

    public interface LoggingContextScope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class ContextScope implements LoggingContextScope {
        private final ContextSnapshot previous;

        ContextScope(@NonNull final ContextSnapshot previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            restore(previous);
        }
    }

    /**
     * ExecutorService decorator that captures the NodeLoggingContext at submission time and
     * reinstalls it on the worker thread before the delegate executes the task.
     * This keeps per-node MDC state intact even when work hops across thread pools.
     */
    private static class ContextPropagatingExecutorService implements ExecutorService {

        private final ExecutorService delegate;

        ContextPropagatingExecutorService(@NonNull final ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(@NonNull final Runnable command) {
            delegate.execute(NodeLoggingContext.wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, @NonNull final TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> Future<T> submit(@NonNull final Callable<T> task) {
            return delegate.submit(NodeLoggingContext.wrap(task));
        }

        @Override
        public <T> Future<T> submit(@NonNull final Runnable task, final T result) {
            return delegate.submit(NodeLoggingContext.wrap(task), result);
        }

        @Override
        public Future<?> submit(@NonNull final Runnable task) {
            return delegate.submit(NodeLoggingContext.wrap(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(@NonNull final Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(wrapCallables(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                @NonNull final Collection<? extends Callable<T>> tasks,
                final long timeout,
                @NonNull final TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(@NonNull final Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return delegate.invokeAny(wrapCallables(tasks));
        }

        @Override
        public <T> T invokeAny(
                @NonNull final Collection<? extends Callable<T>> tasks,
                final long timeout,
                @NonNull final TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
        }

        private <T> Collection<? extends Callable<T>> wrapCallables(
                @NonNull final Collection<? extends Callable<T>> tasks) {
            requireNonNull(tasks, "tasks must not be null");
            final List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
            for (final Callable<T> task : tasks) {
                wrapped.add(NodeLoggingContext.wrap(task));
            }
            return wrapped;
        }
    }

    private static final class ContextPropagatingScheduledExecutorService extends ContextPropagatingExecutorService
            implements ScheduledExecutorService {

        private final ScheduledExecutorService delegate;

        ContextPropagatingScheduledExecutorService(@NonNull final ScheduledExecutorService delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(
                @NonNull final Runnable command, final long delay, @NonNull final TimeUnit unit) {
            return delegate.schedule(NodeLoggingContext.wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                @NonNull final Callable<V> callable, final long delay, @NonNull final TimeUnit unit) {
            return delegate.schedule(NodeLoggingContext.wrap(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                @NonNull final Runnable command,
                final long initialDelay,
                final long period,
                @NonNull final TimeUnit unit) {
            return delegate.scheduleAtFixedRate(NodeLoggingContext.wrap(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                @NonNull final Runnable command,
                final long initialDelay,
                final long delay,
                @NonNull final TimeUnit unit) {
            return delegate.scheduleWithFixedDelay(NodeLoggingContext.wrap(command), initialDelay, delay, unit);
        }
    }
}
