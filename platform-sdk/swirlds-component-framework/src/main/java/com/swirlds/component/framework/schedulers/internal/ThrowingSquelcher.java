// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.internal;

/**
 * A squelcher object that does not support squelching.
 */
public class ThrowingSquelcher implements Squelcher {
    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void startSquelching() {
        throw new UnsupportedOperationException("Squelching is not supported by this task scheduler");
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void stopSquelching() {
        throw new UnsupportedOperationException("Squelching is not supported by this task scheduler");
    }

    /**
     * {@inheritDoc}
     *
     * @return false
     */
    @Override
    public boolean shouldSquelch() {
        return false;
    }
}
