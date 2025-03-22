// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle.reconnect;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class BrokenBuilder implements VirtualDataSourceBuilder {

    private static final long CLASS_ID = 0x5a79654cd0f96dd0L;

    VirtualDataSourceBuilder delegate;

    volatile int numCallsBeforeThrow = Integer.MAX_VALUE;
    volatile int numTimesToBreak = 0;

    // the following fields are volatile to ensure visibility,
    // as BreakableDataSource.saveRecords called from multiple threads.
    volatile int numCalls = 0;
    volatile int numTimesBroken = 0;

    public BrokenBuilder() {}

    public BrokenBuilder(VirtualDataSourceBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        delegate.serialize(out);
        out.writeInt(numCallsBeforeThrow);
        out.writeInt(numTimesToBreak);
        out.writeInt(numCalls);
        out.writeInt(numTimesBroken);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        delegate.deserialize(in, version);
        numCallsBeforeThrow = in.readInt();
        numTimesToBreak = in.readInt();
        numCalls = in.readInt();
        numTimesBroken = in.readInt();
    }

    @Override
    public BreakableDataSource build(final String label, final boolean withDbCompactionEnabled) {
        return new BreakableDataSource(this, delegate.build(label, withDbCompactionEnabled));
    }

    @Override
    public BreakableDataSource copy(
            final VirtualDataSource snapshotMe, final boolean makeCopyActive, final boolean offlineUse) {
        final var breakableSnapshot = (BreakableDataSource) snapshotMe;
        return new BreakableDataSource(this, delegate.copy(breakableSnapshot.delegate, makeCopyActive, offlineUse));
    }

    @Override
    public void snapshot(final Path to, final VirtualDataSource snapshotMe) {
        final var breakableSnapshot = (BreakableDataSource) snapshotMe;
        delegate.snapshot(to, breakableSnapshot.delegate);
    }

    @Override
    public BreakableDataSource restore(final String label, final Path from) {
        return new BreakableDataSource(this, delegate.restore(label, from));
    }

    public void setNumCallsBeforeThrow(final int numCallsBeforeThrow) {
        this.numCallsBeforeThrow = numCallsBeforeThrow;
    }

    public void setNumTimesToBreak(final int numTimesToBreak) {
        this.numTimesToBreak = numTimesToBreak;
    }
}
