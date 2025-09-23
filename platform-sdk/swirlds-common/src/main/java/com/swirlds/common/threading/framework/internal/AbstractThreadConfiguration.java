// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import static com.swirlds.common.threading.framework.config.ThreadConfiguration.captureThreadConfiguration;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.state.Mutable;
import com.swirlds.common.threading.framework.ThreadSeed;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.base.Copyable;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.consensus.model.node.NodeId;

/**
 * Boilerplate getters, setters, and configuration for basic thread configuration.
 *
 * @param <C>
 * 		the type of the class extending this class
 */
public abstract class AbstractThreadConfiguration<C extends AbstractThreadConfiguration<C>>
        implements Copyable, Mutable {

    private static final Logger logger = LogManager.getLogger(AbstractThreadConfiguration.class);

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    /**
     * The ID of the node that is running the thread.
     */
    private NodeId nodeId;

    /**
     * The name of the component with which this thread is associated.
     */
    private String component;

    /**
     * A name for this thread.
     */
    private String threadName;

    /**
     * The thread's fully formatted name. This will be used for the thread name if not null. Otherwise,
     * the thread's name will be derived based on its configuration.
     */
    private String fullyFormattedThreadName;

    /**
     * The ID of the other node if this thread is responsible for a task associated with a
     * particular node.
     */
    private NodeId otherNodeId;

    /**
     * The thread group that will contain new threads.
     */
    private ThreadGroup threadGroup = defaultThreadGroup();

    /**
     * If true then use thread numbers when generating the thread name.
     */
    private boolean useThreadNumbers;

    /**
     * If thread numbers are enabled, this contains the next thread number that should be used.
     */
    private final AtomicInteger nextThreadNumber;

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

    /**
     * Build a new thread configuration with default values.
     */
    protected AbstractThreadConfiguration(final ThreadManager threadManager) {
        this.threadManager = threadManager;
        nextThreadNumber = new AtomicInteger();
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the configuration to copy
     */
    @SuppressWarnings("CopyConstructorMissesField")
    protected AbstractThreadConfiguration(final AbstractThreadConfiguration<C> that) {
        this.threadManager = that.threadManager;
        this.nodeId = that.nodeId;
        this.component = that.component;
        this.threadName = that.threadName;
        this.fullyFormattedThreadName = that.fullyFormattedThreadName;
        this.otherNodeId = that.otherNodeId;
        this.threadGroup = that.threadGroup;
        this.daemon = that.daemon;
        this.priority = that.priority;
        this.contextClassLoader = that.contextClassLoader;
        this.exceptionHandler = that.exceptionHandler;
        this.runnable = that.runnable;
        this.nextThreadNumber = that.nextThreadNumber;
        this.useThreadNumbers = that.useThreadNumbers;
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
     * Get a copy of this configuration. New copy is always mutable,
     * and the mutability status of the original is unchanged.
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

    /**
     * Extracts the thread configuration from a given thread and loads it into this configuration object.
     *
     * @param thread
     * 		the thread to copy configuration from
     */
    protected void copyThreadConfiguration(final Thread thread) {
        setFullyFormattedThreadName(thread.getName());
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
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads.
     * </p>
     *
     * @param start
     * 		if true then start the thread before returning it
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
     * <p>
     * Build a "seed" that can be planted in a thread. When the runnable is executed, it takes over the calling thread
     * and configures that thread the way it would configure a newly created thread via
     * {@link ThreadConfiguration#build()}. When work is finished, the calling thread is restored
     * back to its original configuration.
     * </p>
     *
     * <p>
     * Note that this seed will be unable to change the thread group or daemon status of the calling thread,
     * regardless the values set in this configuration.
     * </p>
     *
     * <p>
     * After calling this method, this configuration object should not be modified or used to construct other
     * threads, factories, or seeds.
     * </p>
     *
     * @return a seed that can be used to inject this thread configuration onto an existing thread.
     */
    protected ThreadSeed buildThreadSeed() {
        requireNonNull(getRunnable(), "runnable must not be null");

        final ContextSnapshot snapshot = captureContextSnapshot();

        return () -> {
            final ThreadConfiguration originalConfiguration = captureThreadConfiguration(threadManager);

            try {
                configureThread(Thread.currentThread());
                wrapRunnableWithSnapshot(Objects.requireNonNull(getRunnable(), "runnable must not be null"), snapshot)
                        .run();
            } finally {
                originalConfiguration.configureThread(Thread.currentThread());
            }
        };
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
     * <p>
     * Construct a thread name. Format is as follows:
     * </p>
     *
     * <pre>
     *  &lt;COMPONENT: NAME NODE_ID to OTHER_ID #THREAD_NUM&gt;
     *   |________| |__| |________| |______| |_________|
     *       |       |         |        |            |
     *       |   "unnamed"     |        |            |
     *       |    if unset     |  omitted if unset   |
     *       |                 |                     |
     * omitted if unset        |           omitted if unset
     *                         |
     * omitted if both self and other node ID is unset,
     * "? to" if only this node's ID is unset
     * </pre>
     *
     * <p>
     * If the fully formatted thread name has been set, then use that thread name instead of the standard format.
     * </p>
     */
    private String buildThreadName() {
        if (fullyFormattedThreadName != null) {
            return fullyFormattedThreadName;
        }

        // The parts are joined together with a space in-between each.
        final List<String> parts = new LinkedList<>();

        final boolean hasComponent = component != null && !component.isBlank();
        final boolean hasName = threadName != null && !threadName.isBlank();
        final boolean hasNode = nodeId != null;
        final boolean hasOtherNode = otherNodeId != null;

        if (hasComponent) {
            parts.add(component + ":");
        }

        if (hasName) {
            parts.add(threadName);
        } else {
            parts.add("unnamed");
        }

        if (hasNode) {
            parts.add(nodeId.toString());
        }

        if (hasOtherNode) {
            if (hasNode) {
                parts.add("to");
            } else {
                parts.add("? to");
            }
            parts.add(otherNodeId.toString());
        }

        if (useThreadNumbers) {
            parts.add("#" + nextThreadNumber.getAndIncrement());
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("<");
        for (int index = 0; index < parts.size(); index++) {
            sb.append(parts.get(index));
            if (index + 1 < parts.size()) {
                sb.append(" ");
            }
        }
        sb.append(">");

        return sb.toString();
    }

    /**
     * Builds a default uncaught exception handler.
     */
    private static Thread.UncaughtExceptionHandler buildDefaultExceptionHandler() {
        return (Thread t, Throwable e) -> logger.error(EXCEPTION.getMarker(), "exception on thread {}", t.getName(), e);
    }

    /**
     * Configure thread properties. This method is able to set all properties for an unstarted thread
     * except for thread group. If the thread has already been started, then this method will also not
     * configure daemon status.
     *
     * @param thread
     * 		the thread to configure
     */
    protected void configureThread(final Thread thread) {
        thread.setName(buildThreadName());
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
     * Get the node ID that will run threads created by this object.
     */
    @NonNull
    public NodeId getNodeId() {
        return nodeId;
    }

    /**
     * Set the node ID.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setNodeId(@NonNull final NodeId nodeId) {
        throwIfImmutable();
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        this.nodeId = nodeId;
        return (C) this;
    }

    /**
     * Get the name of the component that new threads will be associated with.
     */
    public String getComponent() {
        return component;
    }

    /**
     * Set the name of the component that new threads will be associated with.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setComponent(final String component) {
        throwIfImmutable();

        this.component = component;
        return (C) this;
    }

    /**
     * Get the name for created threads.
     */
    public String getThreadName() {
        return threadName;
    }

    /**
     * Set the name for created threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setThreadName(final String threadName) {
        throwIfImmutable();

        this.threadName = threadName;
        return (C) this;
    }

    /**
     * Get the fully formatted thread name, or null if the fully formatted name has not been specified.
     *
     * @return the thread name exactly as it will appear (if specified), otherwise null
     */
    public String getFullyFormattedThreadName() {
        return fullyFormattedThreadName;
    }

    /**
     * Specify the thread name in its fully formatted state. If null, then the thread will be named
     * algorithmically using the thread's configuration.
     *
     * @param fullyFormattedThreadName
     * 		the exact thread name as it will appear, or null if the thread should
     * 		use an algorithmically generated name
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setFullyFormattedThreadName(final String fullyFormattedThreadName) {
        throwIfImmutable();

        this.fullyFormattedThreadName = fullyFormattedThreadName;
        return (C) this;
    }

    /**
     * Get the node ID of the other node (if created threads will be dealing with a task related to a specific node).
     */
    @NonNull
    public NodeId getOtherNodeId() {
        return otherNodeId;
    }

    /**
     * Set the node ID of the other node (if created threads will be dealing with a task related to a specific node).
     * Ignored if null.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setOtherNodeId(@NonNull final NodeId otherNodeId) {
        throwIfImmutable();
        Objects.requireNonNull(otherNodeId, "otherNodeId must not be null");

        this.otherNodeId = otherNodeId;
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
     * Set the runnable that will be executed on the thread. If the runnable throws an interrupt,
     * then the thread's interrupted flag will be set and the runnable will return.
     *
     * @param runnable
     * 		a runnable that may throw an interrupt
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
     * Check if this configuration is immutable. A configuration becomes immutable once it is used to create
     * a thread, a factory, or a seed.
     *
     * @return if this configuration is immutable
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * If this method is called then thread numbers will be used when naming the threads.
     */
    protected void enableThreadNumbering() {
        throwIfImmutable();
        useThreadNumbers = true;
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
     * Captured Log4j MDC state consisting of the context map and stack at the moment a task is wrapped.
     * Each executor worker restores this snapshot before running a task and reinstates the previous
     * values afterwards so diagnostic context survives thread hops.
     */
    protected record ContextSnapshot(@NonNull Map<String, String> map, @NonNull List<String> stack) {}
}
