// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A squelcher that actually supports squelching.
 */
public class DefaultSquelcher implements Squelcher {
    /**
     * Whether or not tasks should actively be squelched.
     */
    private final AtomicBoolean squelchFlag = new AtomicBoolean(false);

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {
        if (!squelchFlag.compareAndSet(false, true)) {
            throw new IllegalStateException("Scheduler is already squelching");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {
        if (!squelchFlag.compareAndSet(true, false)) {
            throw new IllegalStateException("Scheduler is not currently squelching");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldSquelch() {
        return squelchFlag.get();
    }
}
