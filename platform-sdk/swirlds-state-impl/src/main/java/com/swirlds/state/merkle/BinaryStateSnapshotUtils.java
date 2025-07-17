// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotWriter;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.BinaryState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Utility class for creating and loading snapshots of a {@link BinaryState}.
 * <p>
 * This class provides methods to create a snapshot of a  {@link BinaryState} and to load a b {@link BinaryState} from a snapshot.
 * The state must be hashed and immutable before calling the snapshot creation method.
 */
public final class BinaryStateSnapshotUtils {

    /**
     * Creates a snapshot for the state. The state has to be hashed and immutable before calling this method.
     *
     * @param targetPath The path to save the snapshot.
     * @param state      the state to create a snapshot for
     */
    public static void createSnapshot(@NonNull Path targetPath, VirtualMapBinaryState state) throws IOException {
        state.getVirtualMap().throwIfMutable();
        state.getVirtualMap().throwIfDestroyed();

        MerkleTreeSnapshotWriter.createSnapshot(state.getVirtualMap(), targetPath);
    }

    /**
     * Loads a snapshot of a state.
     *
     * @param targetPath The path to load the snapshot from.
     */
    public static BinaryState loadSnapshot(
            @NonNull Path targetPath, @NonNull Configuration configuration, @Nullable Metrics metrics)
            throws IOException {
        final MerkleNode root = MerkleTreeSnapshotReader.readStateFileData(configuration, targetPath)
                .stateRoot();
        if (!(root instanceof VirtualMap readVirtualMap)) {
            throw new IllegalStateException(
                    "Root should be a VirtualMap, but it is " + root.getClass().getSimpleName() + " instead");
        }
        final var mutableCopy = readVirtualMap.copy();
        if (metrics != null) {
            mutableCopy.registerMetrics(metrics);
        }
        readVirtualMap.release();
        readVirtualMap = mutableCopy;

        return new VirtualMapBinaryState(readVirtualMap);
    }

    private BinaryStateSnapshotUtils() {
        // Utility class, no instantiation
    }
}
