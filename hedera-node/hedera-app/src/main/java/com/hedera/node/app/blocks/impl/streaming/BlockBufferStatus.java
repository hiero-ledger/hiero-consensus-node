// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Represents the status of the block buffer.
 *
 * @param timestamp the timestamp associated with the status information
 * @param saturationPercent the amount of saturation the block buffer is experiencing, as a percentage
 * @param isActionStage true if the buffer is considered at an action stage and corrective actions should be taken,
 *                      else false
 */
public record BlockBufferStatus(@NonNull Instant timestamp, double saturationPercent, boolean isActionStage) {
    public BlockBufferStatus {
        requireNonNull(timestamp, "Timestamp is required");
    }
}
