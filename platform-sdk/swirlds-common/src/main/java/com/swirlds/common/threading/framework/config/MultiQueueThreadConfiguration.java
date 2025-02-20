// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.config;

import com.swirlds.common.threading.framework.MultiQueueThread;
import com.swirlds.common.threading.framework.internal.AbstractMultiQueueThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import java.util.function.Consumer;

/**
 * Configures and builds a {@link MultiQueueThread}.
 */
public class MultiQueueThreadConfiguration
        extends AbstractMultiQueueThreadConfiguration<MultiQueueThreadConfiguration> {

    /**
     * Create a new multi thread queue configuration.
     *
     * @param threadManager
     * 		the thread manager, responsible for creating new threads
     */
    public MultiQueueThreadConfiguration(final ThreadManager threadManager) {
        super(threadManager);
    }

    /**
     * Copy constructor.
     */
    private MultiQueueThreadConfiguration(final MultiQueueThreadConfiguration other) {
        super(other);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MultiQueueThreadConfiguration copy() {
        return new MultiQueueThreadConfiguration(this);
    }

    /**
     * Build a wrapped queue thread that is capable of handling multiple data types. Behaves more or less
     * like a regular queue thread, but with some helpful boilerplate code.
     *
     * @return a wrapped queue thread
     */
    public MultiQueueThread build() {
        return build(false);
    }

    /**
     * Build a wrapped queue thread that is capable of handling multiple data types. Behaves more or less
     * like a regular queue thread, but with some helpful boilerplate code.
     *
     * @param start
     * 		if true then automatically start the thread
     * @return a wrapped queue thread
     */
    public MultiQueueThread build(final boolean start) {
        return this.buildMultiQueue(start);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> MultiQueueThreadConfiguration addHandler(final Class<T> clazz, final Consumer<T> handler) {
        return super.addHandler(clazz, handler);
    }
}
