// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.state.Mutable;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.base.Copyable;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.consensus.concurrent.manager.ThreadManager;

/**
 * Boilerplate getters, setters, and configuration for basic thread configuration.
 *
 * @param <C> the type of the class extending this class
 */
public abstract class AbstractThreadConfiguration<C extends AbstractThreadConfiguration<C>>
        implements Copyable, Mutable {

    private static final Logger logger = LogManager.getLogger(AbstractThreadConfiguration.class);

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    /**
     * The thread group that will contain new threads.
     */
    private ThreadGroup threadGroup = defaultThreadGroup();

    /**
     * If new threads are daemons or not.
     */
    private boolean daemon = true;

    /**
     * The priority for new threads.
     */
    private int priority = Thread.NORM_PRIORITY;

    /**
     * The classloader for new threads.
     */
    private ClassLoader contextClassLoader;

    /**
     * The exception handler for new threads.
     */
    private Thread.UncaughtExceptionHandler exceptionHandler;

    /**
     * The runnable that will be executed on the thread.
     */
    private Runnable runnable;

    /**
     * Once the first thread is created, this configuration becomes immutable.
     */
    private boolean immutable;

    protected Supplier<String> threadNameProvider = UndefinedThreadNameProvider.instance();

    /**
     * Build a new thread configuration with default values.
     */
    protected AbstractThreadConfiguration(final ThreadManager threadManager) {
        this.threadManager = threadManager;
    }

    /**
     * Copy constructor.
     *
     * @param that the configuration to copy
     */
    @SuppressWarnings("CopyConstructorMissesField")
    protected AbstractThreadConfiguration(final AbstractThreadConfiguration<C> that) {
        this.threadManager = that.threadManager;
        this.threadNameProvider = that.threadNameProvider;
        this.threadGroup = that.threadGroup;
        this.daemon = that.daemon;
        this.priority = that.priority;
        this.contextClassLoader = that.contextClassLoader;
        this.exceptionHandler = that.exceptionHandler;
        this.runnable = that.runnable;
    }

    /**
     * Get the thread manager responsible for creating threads.
     *
     * @return a thread factory
     */
    protected ThreadManager getThreadManager() {
        return threadManager;
    }

    /**
     * Get a copy of this configuration. New copy is always mutable, and the mutability status of the original is
     * unchanged.
     *
     * @return a copy of this configuration
     */
    @SuppressWarnings("unchecked")
    @Override
    public abstract AbstractThreadConfiguration<C> copy();

    /**
     * Make the configuration immutable. Throws if the thread is already immutable.
     */
    protected void becomeImmutable() {
        throwIfImmutable();
        immutable = true;
    }

    @SuppressWarnings("unchecked")
    public C setSingleThreadName(final String threadName) {
        throwIfImmutable();
        this.threadNameProvider = () -> threadName;
        return (C) this;
    }

    @SuppressWarnings("unchecked")
    public C setThreadNameProvider(final Supplier<String> threadNameProvider) {
        throwIfImmutable();
        this.threadNameProvider = threadNameProvider;
        return (C) this;
    }

    /**
     * Extracts the thread configuration from a given thread and loads it into this configuration object.
     *
     * @param thread the thread to copy configuration from
     */
    protected void copyThreadConfiguration(final Thread thread) {
        setSingleThreadName(thread.getName());
        setDaemon(thread.isDaemon());
        setPriority(thread.getPriority());
        setExceptionHandler(thread.getUncaughtExceptionHandler());
        setContextClassLoader(thread.getContextClassLoader());
        setThreadGroup(thread.getThreadGroup());
    }

    /**
     * <p>
     * Build a new thread.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other threads.
     * </p>
     *
     * @param start if true then start the thread before returning it
     * @return a stoppable thread built using this configuration
     */
    protected Thread buildThread(final boolean start) {
        final Runnable runnable = requireNonNull(getRunnable(), "runnable must not be null");
        final ContextSnapshot snapshot = captureContextSnapshot();
        final Runnable contextAwareRunnable = wrapRunnableWithSnapshot(runnable, snapshot);
        final Thread thread = threadManager.createThread(getThreadGroup(), contextAwareRunnable);
        configureThread(thread);

        if (start) {
            thread.start();
        }

        return thread;
    }

    /**
     * Get the default thread group that will be used if there is no user provided thread group
     */
    private static ThreadGroup defaultThreadGroup() {
        final SecurityManager securityManager = System.getSecurityManager();
        if (System.getSecurityManager() == null) {
            return Thread.currentThread().getThreadGroup();
        } else {
            return securityManager.getThreadGroup();
        }
    }

    /**
     * Builds a default uncaught exception handler.
     */
    private static Thread.UncaughtExceptionHandler buildDefaultExceptionHandler() {
        return (Thread t, Throwable e) -> logger.error(EXCEPTION.getMarker(), "exception on thread {}", t.getName(), e);
    }

    /**
     * Configure thread properties. This method is able to set all properties for an unstarted thread except for thread
     * group. If the thread has already been started, then this method will also not configure daemon status.
     *
     * @param thread the thread to configure
     */
    protected void configureThread(final Thread thread) {
        thread.setName(threadNameProvider.get());
        if (!thread.isAlive()) {
            // Daemon status can only be configured before a thread starts.
            thread.setDaemon(isDaemon());
        }
        thread.setPriority(getPriority());
        thread.setUncaughtExceptionHandler(getExceptionHandler());
        if (getContextClassLoader() != null) {
            thread.setContextClassLoader(getContextClassLoader());
        }
    }

    /**
     * Get the the thread group that new threads will be created in.
     */
    public ThreadGroup getThreadGroup() {
        return threadGroup;
    }

    /**
     * Set the the thread group that new threads will be created in.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setThreadGroup(final ThreadGroup threadGroup) {
        throwIfImmutable();

        this.threadGroup = threadGroup;
        return (C) this;
    }

    /**
     * Get the daemon behavior of new threads.
     */
    public boolean isDaemon() {
        return daemon;
    }

    /**
     * Set the daemon behavior of new threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setDaemon(final boolean daemon) {
        throwIfImmutable();

        this.daemon = daemon;
        return (C) this;
    }

    /**
     * Get the priority of new threads.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Set the priority of new threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setPriority(final int priority) {
        throwIfImmutable();

        this.priority = priority;
        return (C) this;
    }

    /**
     * Get the class loader for new threads.
     */
    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    /**
     * Set the class loader for new threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setContextClassLoader(final ClassLoader contextClassLoader) {
        throwIfImmutable();

        this.contextClassLoader = contextClassLoader;
        return (C) this;
    }

    /**
     * Get the exception handler for new threads.
     */
    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return exceptionHandler == null ? buildDefaultExceptionHandler() : exceptionHandler;
    }

    /**
     * Set the exception handler for new threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setExceptionHandler(final Thread.UncaughtExceptionHandler exceptionHandler) {
        throwIfImmutable();

        this.exceptionHandler = exceptionHandler;
        return (C) this;
    }

    /**
     * Get the runnable that will be executed on the thread.
     */
    protected Runnable getRunnable() {
        return runnable;
    }

    /**
     * Set the runnable that will be executed on the thread.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    protected C setRunnable(final Runnable runnable) {
        throwIfImmutable();

        this.runnable = runnable;
        return (C) this;
    }

    /**
     * Set the runnable that will be executed on the thread. If the runnable throws an interrupt, then the thread's
     * interrupted flag will be set and the runnable will return.
     *
     * @param runnable a runnable that may throw an interrupt
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setInterruptableRunnable(final InterruptableRunnable runnable) {
        throwIfImmutable();

        this.runnable = () -> {
            try {
                runnable.run();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        return (C) this;
    }

    /**
     * Check if this configuration is immutable. A configuration becomes immutable once it is used to create a thread, a
     * factory, or a seed.
     *
     * @return if this configuration is immutable
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    protected ContextSnapshot captureContextSnapshot() {
        return new ContextSnapshot(
                new HashMap<>(ThreadContext.getImmutableContext()),
                new ArrayList<>(ThreadContext.getImmutableStack().asList()));
    }

    protected Runnable wrapRunnableWithSnapshot(
            @NonNull final Runnable runnable, @NonNull final ContextSnapshot snapshot) {
        return () -> {
            final ContextSnapshot previous = captureContextSnapshot();
            applyContextSnapshot(snapshot);
            try {
                runnable.run();
            } finally {
                applyContextSnapshot(previous);
            }
        };
    }

    private void applyContextSnapshot(@NonNull final ContextSnapshot snapshot) {
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

    /**
     * Captured Log4j MDC state consisting of the context map and stack at the moment a task is wrapped. Each executor
     * worker restores this snapshot before running a task and reinstates the previous values afterwards so diagnostic
     * context survives thread hops.
     */
    protected record ContextSnapshot(
            @NonNull Map<String, String> map, @NonNull List<String> stack) {}
}
