// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures.datasource;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Helper class to wrap {@link VirtualDataSource} to override some methods for testing.
 */
public class DelegateVirtualDataSource implements VirtualDataSource {

    private final VirtualDataSource delegate;

    public DelegateVirtualDataSource(VirtualDataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @NonNull
    public VirtualDataSource getDelegate() {
        return delegate;
    }

    @Override
    public void close(boolean keepData) throws IOException {
        delegate.close(keepData);
    }

    @Override
    public void saveRecords(
            long firstLeafPath,
            long lastLeafPath,
            @NonNull Stream<VirtualHashChunk> hashChunksToUpdate,
            @NonNull Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull Stream<VirtualLeafBytes> leafRecordsToDelete,
            boolean isReconnectContext)
            throws IOException {
        delegate.saveRecords(
                firstLeafPath,
                lastLeafPath,
                hashChunksToUpdate,
                leafRecordsToAddOrUpdate,
                leafRecordsToDelete,
                isReconnectContext);
    }

    @Override
    @Nullable
    public VirtualLeafBytes loadLeafRecord(Bytes keyBytes) throws IOException {
        return delegate.loadLeafRecord(keyBytes);
    }

    @Override
    @Nullable
    public VirtualLeafBytes loadLeafRecord(long path) throws IOException {
        return delegate.loadLeafRecord(path);
    }

    @Override
    public long findKey(Bytes keyBytes) throws IOException {
        return delegate.findKey(keyBytes);
    }

    @Override
    public @Nullable VirtualHashChunk loadHashChunk(long chunkId) throws IOException {
        return delegate.loadHashChunk(chunkId);
    }

    @Override
    public void snapshot(Path snapshotDirectory) throws IOException {
        delegate.snapshot(snapshotDirectory);
    }

    @Override
    public void copyStatisticsFrom(VirtualDataSource that) {
        delegate.copyStatisticsFrom(that);
    }

    @Override
    public void registerMetrics(Metrics metrics) {
        delegate.registerMetrics(metrics);
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
    public long getFirstLeafPath() {
        return delegate.getFirstLeafPath();
    }

    @Override
    public long getLastLeafPath() {
        return delegate.getLastLeafPath();
    }

    @Override
    public int getHashChunkHeight() {
        return delegate.getHashChunkHeight();
    }
}
