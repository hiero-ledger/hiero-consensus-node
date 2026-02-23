// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.pipeline;

import com.swirlds.virtualmap.internal.AbstractVirtualRoot;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualRoot;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * A bare-bones implementation of {@link VirtualRoot} that doesn't do much of anything.
 */
@ConstructableIgnored
public final class NoOpVirtualRoot extends AbstractVirtualRoot implements VirtualRoot {

    /**
     * Transform this object into an immutable one.
     */
    public void makeImmutable() {
        setImmutable(true);
    }

    @Override
    public long getClassId() {
        return 0;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public NoOpVirtualRoot copy() {
        return null;
    }

    @Override
    public boolean shouldBeFlushed() {
        return false;
    }

    @Override
    public void flush() {}

    @Override
    public boolean isFlushed() {
        return false;
    }

    @Override
    public void waitUntilFlushed() {}

    @Override
    public void merge() {}

    @Override
    public boolean isMerged() {
        return false;
    }

    @Override
    public boolean isHashed() {
        return false;
    }

    @Override
    public void computeHash() {}

    @Override
    public RecordAccessor detach() {
        return null;
    }

    @Override
    public boolean isRegisteredToPipeline(final VirtualPipeline pipeline) {
        return true;
    }

    @Override
    public void onShutdown(final boolean immediately) {}
}
