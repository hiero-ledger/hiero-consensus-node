// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A utility class for testing purposes. Each named {@link InMemoryDataSource} is stored in a map.
 */
public class InMemoryBuilder implements VirtualDataSourceBuilder {

    private final Map<String, InMemoryDataSource> databases = new ConcurrentHashMap<>();

    // Path to data source, used in snapshot() and restore()
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
    public InMemoryDataSource build(final String label, final boolean withDbCompactionEnabled) {
        return databases.computeIfAbsent(label, (s) -> createDataSource(label));
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InMemoryDataSource copy(
            final VirtualDataSource snapshotMe, final boolean makeCopyActive, final boolean offlineUse) {
        final InMemoryDataSource source = (InMemoryDataSource) snapshotMe;
        final InMemoryDataSource snapshot = new InMemoryDataSource(source);
        databases.put(createUniqueDataSourceName(source.getName()), snapshot);
        return snapshot;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public void snapshot(final Path to, final VirtualDataSource snapshotMe) {
        final InMemoryDataSource source = (InMemoryDataSource) snapshotMe;
        final InMemoryDataSource snapshot = new InMemoryDataSource(source);
        snapshots.put(to.toString(), snapshot);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualDataSource restore(final String label, final Path from) {
        return snapshots.get(from.toString());
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

    private String createUniqueDataSourceName(final String name) {
        return name + "-" + System.currentTimeMillis();
    }
}
