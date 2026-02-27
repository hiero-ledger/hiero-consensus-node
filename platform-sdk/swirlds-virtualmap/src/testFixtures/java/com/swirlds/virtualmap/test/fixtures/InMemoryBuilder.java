// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility class for testing purposes. Each named {@link InMemoryDataSource} is stored in a map.
 */
public class InMemoryBuilder implements VirtualDataSourceBuilder {

    private static final AtomicInteger dbIndex = new AtomicInteger(0);

    // Path to data source, used in snapshot() and restore()
    private static final Map<String, InMemoryDataSource> snapshots = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InMemoryDataSource build(
            final String label,
            @Nullable final Path sourceDir,
            final boolean compactionEnabled,
            final boolean offlineUse) {
        if (sourceDir == null) {
            return createDataSource(label);
        } else {
            assert snapshots.containsKey(sourceDir.toString());
            return snapshots.get(sourceDir.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Path snapshot(@Nullable Path destinationDir, @NonNull final VirtualDataSource snapshotMe) {
        if (destinationDir == null) {
            // This doesn't have to be a real path, it's only used a key in the databases field
            destinationDir = Path.of("inmemory_db_" + dbIndex.getAndIncrement());
        }
        final InMemoryDataSource source = (InMemoryDataSource) snapshotMe;
        final InMemoryDataSource snapshot = new InMemoryDataSource(source);
        snapshots.put(destinationDir.toString(), snapshot);
        return destinationDir;
    }

    protected InMemoryDataSource createDataSource(final String name) {
        return new InMemoryDataSource(name);
    }
}
