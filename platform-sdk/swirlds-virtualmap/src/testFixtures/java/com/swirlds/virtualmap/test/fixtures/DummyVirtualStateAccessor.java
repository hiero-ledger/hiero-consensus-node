// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;

import com.swirlds.virtualmap.internal.VirtualStateAccessor;

public class DummyVirtualStateAccessor implements VirtualStateAccessor {
    private long firstLeafPath = INVALID_PATH;
    private long lastLeafPath = INVALID_PATH;

    public DummyVirtualStateAccessor() {}

    @Override
    public String getLabel() {
        return "DummyLabel";
    }

    @Override
    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    @Override
    public long getLastLeafPath() {
        return lastLeafPath;
    }

    @Override
    public void setFirstLeafPath(final long path) {
        this.firstLeafPath = path;
    }

    @Override
    public void setLastLeafPath(final long path) {
        this.lastLeafPath = path;
    }

    @Override
    public long size() {
        return lastLeafPath == INVALID_PATH ? 0 : (lastLeafPath - firstLeafPath + 1);
    }
}
