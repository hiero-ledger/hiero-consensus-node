// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.pipeline;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.virtualmap.internal.RecordAccessor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A bare-bones implementation of {@link VirtualRoot} that doesn't do much of anything.
 */
@ConstructableIgnored
public final class NoOpVirtualRoot extends PartialMerkleLeaf implements VirtualRoot, MerkleLeaf {

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
    public void serialize(final SerializableDataOutputStream out) throws IOException {}

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {}

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
    public void snapshot(@NonNull final Path destination) {}

    @Override
    public boolean isDetached() {
        return false;
    }

    @Override
    public boolean isRegisteredToPipeline(final VirtualPipeline pipeline) {
        return true;
    }

    @Override
    public void onShutdown(final boolean immediately) {}
}
