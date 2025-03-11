// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

/**
 * A counter that doesn't actually count. Saves us from having to do a (counter == null) check in the standard case.
 */
public class NoOpObjectCounter extends ObjectCounter {

    private static final NoOpObjectCounter INSTANCE = new NoOpObjectCounter();

    /**
     * Get the singleton instance.
     *
     * @return the singleton instance
     */
    public static NoOpObjectCounter getInstance() {
        return INSTANCE;
    }

    /**
     * Constructor.
     */
    private NoOpObjectCounter() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRamp(final long delta) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean attemptOnRamp(final long delta) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceOnRamp(final long delta) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void offRamp(final long delta) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        return COUNT_UNDEFINED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilEmpty() {}
}
