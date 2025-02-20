// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;

import com.swirlds.virtualmap.internal.VirtualStateAccessor;

public class ReconnectState implements VirtualStateAccessor {
    private long firstLeafPath;
    private long lastLeafPath;

    public ReconnectState(long firstLeafPath, long lastLeafPath) {
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        throw new UnsupportedOperationException("Not called");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLastLeafPath() {
        return lastLeafPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFirstLeafPath(final long path) {
        this.firstLeafPath = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastLeafPath(final long path) {
        this.lastLeafPath = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long size() {
        return lastLeafPath == INVALID_PATH ? 0 : (lastLeafPath - firstLeafPath + 1);
    }
}
