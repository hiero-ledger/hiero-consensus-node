// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class BreakableDataSource implements VirtualDataSource {

    final VirtualDataSource delegate;
    private final BrokenBuilder builder;

    public BreakableDataSource(final BrokenBuilder builder, final VirtualDataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.builder = Objects.requireNonNull(builder);
    }

    @Override
    public void saveRecords(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final Stream<VirtualHashRecord> pathHashRecordsToUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
            final boolean isReconnectContext)
            throws IOException {
        final List<VirtualLeafBytes> leaves = leafRecordsToAddOrUpdate.toList();

        if (builder.numTimesBroken < builder.numTimesToBreak) {
            // Synchronization block is not required here, as this code is never called in parallel
            // (though from different threads). `volatile` modifier is sufficient to ensure visibility.
            builder.numCalls += leaves.size();
            if (builder.numCalls > builder.numCallsBeforeThrow) {
                builder.numCalls = 0;
                builder.numTimesBroken++;
                delegate.close();
                throw new IOException("Something bad on the DB!");
            }
        }

        delegate.saveRecords(
                firstLeafPath,
                lastLeafPath,
                pathHashRecordsToUpdate,
                leaves.stream(),
                leafRecordsToDelete,
                isReconnectContext);
    }

    @Override
    public void close(final boolean keepData) throws IOException {
        delegate.close(keepData);
    }

    @Override
    public VirtualLeafBytes loadLeafRecord(final Bytes key, final int keyHashCode) throws IOException {
        return delegate.loadLeafRecord(key, keyHashCode);
    }

    @Override
    public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
        return delegate.loadLeafRecord(path);
    }

    @Override
    public long findKey(final Bytes key, final int keyHashCode) throws IOException {
        return delegate.findKey(key, keyHashCode);
    }

    @Override
    public Hash loadHash(final long path) throws IOException {
        return delegate.loadHash(path);
    }

    @Override
    public boolean loadAndWriteHash(long path, SerializableDataOutputStream out) throws IOException {
        return delegate.loadAndWriteHash(path, out);
    }

    @Override
    public void snapshot(final Path snapshotDirectory) throws IOException {
        delegate.snapshot(snapshotDirectory);
    }

    @Override
    public void copyStatisticsFrom(final VirtualDataSource that) {}

    @Override
    public void registerMetrics(final Metrics metrics) {}

    @Override
    public long getFirstLeafPath() {
        return delegate.getFirstLeafPath();
    }

    @Override
    public long getLastLeafPath() {
        return delegate.getLastLeafPath();
    }

    @Override
    public void enableBackgroundCompaction() {
        delegate.enableBackgroundCompaction();
    }

    @Override
    public void stopAndDisableBackgroundCompaction() {
        delegate.stopAndDisableBackgroundCompaction();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public KeySerializer getKeySerializer() {
        throw new UnsupportedOperationException("This method should never be called");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public ValueSerializer getValueSerializer() {
        throw new UnsupportedOperationException("This method should never be called");
    }
}
