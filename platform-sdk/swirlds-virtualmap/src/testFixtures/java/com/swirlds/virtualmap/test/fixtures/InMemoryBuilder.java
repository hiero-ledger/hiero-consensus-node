// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A utility class for testing purposes. Each named {@link InMemoryDataSource} is stored in a map.
 */
public class InMemoryBuilder implements VirtualDataSourceBuilder {

    private final Map<String, InMemoryDataSource> databases = new ConcurrentHashMap<>();

    private static final AtomicInteger dbIndex = new AtomicInteger(0);

    private static final Map<String, InMemoryDataSource> snapshots = new ConcurrentHashMap<>();

    private static final long CLASS_ID = 0x29e653a8c81959b8L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

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
            return databases.computeIfAbsent(label, (s) -> createDataSource(label));
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

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        // no configuration data to serialize
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        // no configuration data to deserialize
    }

    protected InMemoryDataSource createDataSource(final String name) {
        return new InMemoryDataSource(name);
    }
}
