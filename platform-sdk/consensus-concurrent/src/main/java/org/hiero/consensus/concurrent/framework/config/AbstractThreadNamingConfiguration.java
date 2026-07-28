// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Basic implementation of ThreadNamingConfiguration, providing thread numbering and immutability support
 */
public abstract class AbstractThreadNamingConfiguration<T extends AbstractThreadNamingConfiguration<T>>
        implements ThreadNamingConfiguration<T> {

    /**
     * Is this instance effectively immutable and should be giving errors on any modifications?
     */
    private boolean immutable;

    /**
     * If true then use thread numbers when generating the thread name.
     */
    protected boolean useThreadNumbers;
    /**
     * If thread numbers are enabled, this contains the next thread number that should be used.
     */
    protected final AtomicInteger nextThreadNumber;

    /**
     * Create a new instance
     */
    public AbstractThreadNamingConfiguration() {
        this.nextThreadNumber = new AtomicInteger();
    }

    /**
     * Make the configuration immutable. Throws if the thread is already immutable.
     */
    public void becomeImmutable() {
        throwIfImmutable();
        immutable = true;
    }

    /**
     * If this method is called then thread numbers will be used when naming the threads.
     */
    public void enableThreadNumbering() {
        throwIfImmutable();
        useThreadNumbers = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract T copy();

    /**
     * {@inheritDoc}
     */
    public abstract String generateNextThreadName();
}
