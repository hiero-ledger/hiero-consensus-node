// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.common.threading.framework.MultiQueueThread;
import com.swirlds.common.threading.manager.ThreadManager;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A base class for configurations that build {@link MultiQueueThread}s.
 *
 * @param <C>
 * 		the type of the concrete class
 */
public abstract class AbstractMultiQueueThreadConfiguration<C extends AbstractQueueThreadConfiguration<C, Object>>
        extends AbstractQueueThreadConfiguration<C, Object> {

    /**
     * A map of data type to handler for that type.
     */
    private final Map<Class<?>, Consumer<Object>> subHandlers = new HashMap<>();

    /**
     * Construct a new instance.
     *
     * @param threadManager
     * 		the thread manager, responsible for creating new threads
     */
    protected AbstractMultiQueueThreadConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     */
    protected AbstractMultiQueueThreadConfiguration(final AbstractMultiQueueThreadConfiguration<C> other) {
        super(other);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract AbstractMultiQueueThreadConfiguration<C> copy();

    /**
     * Build the multi-queue.
     *
     * @param start
     * 		if true then automatically start the queue thread
     * @return the multi queue
     */
    protected MultiQueueThread buildMultiQueue(final boolean start) {
        return new MultiQueueThreadImpl(subHandlers, handler -> {
            setHandler(handler);
            return buildQueueThread(start);
        });
    }

    /**
     * Add a handler for a particular data type and create the corresponding inserter.
     *
     * @param clazz
     * 		the class of the data type
     * @param handler
     * 		the handler for the data type
     * @return this object
     */
    @SuppressWarnings("unchecked")
    protected <T> C addHandler(final Class<T> clazz, final Consumer<T> handler) {
        final Consumer<?> prev = subHandlers.put(clazz, (Consumer<Object>) handler);
        if (prev != null) {
            throw new IllegalStateException("Handler already exists for " + clazz);
        }
        return (C) this;
    }
}
