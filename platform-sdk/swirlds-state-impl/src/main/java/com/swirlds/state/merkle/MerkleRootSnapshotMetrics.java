// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class encapsulates metrics for the Merkle root snapshot.
 */
public class MerkleRootSnapshotMetrics {
    private static final RunningAverageMetric.Config WRITE_MERKLE_ROOT_TO_DISK_TIME_CONFIG =
            new RunningAverageMetric.Config("platform", "writeMerkleRootToDisk")
                    .withDescription("average time it takes to write a Merkle tree to disk (in milliseconds)")
                    .withUnit("ms");

    private final RunningAverageMetric writeMerkleRootToDiskTime;
    /**
     * Constructor.
     *
     * @param metrics the metrics object
     */
    public MerkleRootSnapshotMetrics(@NonNull final Metrics metrics) {
        writeMerkleRootToDiskTime = metrics.getOrCreate(WRITE_MERKLE_ROOT_TO_DISK_TIME_CONFIG);
    }

    /**
     * No-arg constructor constructs an object that does not track metrics.
     */
    public MerkleRootSnapshotMetrics() {
        writeMerkleRootToDiskTime = null;
    }

    /**
     * Update the metric tracking the average time required to write a Merkle tree to disk.
     * @param timeTakenMs the time taken to write the state to disk
     */
    public void updateWriteStateToDiskTimeMetric(final long timeTakenMs) {
        if (writeMerkleRootToDiskTime != null) {
            writeMerkleRootToDiskTime.update(timeTakenMs);
        }
    }
}
