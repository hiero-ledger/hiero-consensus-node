// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import com.swirlds.merkledb.config.MerkleDbConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.file.FileSystemManager;

/** LongList implementations that can be selected by benchmarks and diagnostic tooling. */
public enum LongListImplementation {
    HEAP,
    OFF_HEAP,
    SEGMENT,
    DISK,
    DISK_SEGMENT;

    /** Creates an empty LongList of this implementation. */
    public LongList create(
            final long capacity,
            @NonNull final MerkleDbConfig configuration,
            @NonNull final FileSystemManager fileSystemManager) {
        return switch (this) {
            case HEAP -> new LongListHeap(capacity, configuration);
            case OFF_HEAP -> new LongListOffHeap(capacity, configuration);
            case SEGMENT -> new LongListSegment(capacity, configuration);
            case DISK -> new LongListDisk(capacity, configuration, fileSystemManager);
            case DISK_SEGMENT -> new LongListDiskSegment(capacity, configuration, fileSystemManager);
        };
    }

    /** Creates a LongList of this implementation from a snapshot file. */
    public LongList load(
            @NonNull final Path file,
            final long capacity,
            @NonNull final MerkleDbConfig configuration,
            @NonNull final FileSystemManager fileSystemManager)
            throws IOException {
        return switch (this) {
            case HEAP -> new LongListHeap(file, capacity, configuration);
            case OFF_HEAP -> new LongListOffHeap(file, capacity, configuration);
            case SEGMENT -> new LongListSegment(file, capacity, configuration);
            case DISK -> new LongListDisk(file, capacity, configuration, fileSystemManager);
            case DISK_SEGMENT -> new LongListDiskSegment(file, capacity, configuration, fileSystemManager);
        };
    }

    /** Returns whether this implementation keeps its index contents in a backing file. */
    public boolean isDiskBased() {
        return this == DISK || this == DISK_SEGMENT;
    }
}
