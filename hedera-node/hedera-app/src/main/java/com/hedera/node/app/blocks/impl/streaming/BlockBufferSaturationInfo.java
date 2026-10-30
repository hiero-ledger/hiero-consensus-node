// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Information related to saturation within the block buffer.
 *
 * @param timestamp the timestamp of when the included information was gathered
 * @param unackedBlocksSaturation details related to unacknowledged block count saturation
 * @param unackedBytesSaturation details related to unacknowledged block bytes saturation
 * @param inProgressBlocksSaturation details related to in-progress blocks saturation
 */
public record BlockBufferSaturationInfo(
        @NonNull Instant timestamp,
        @NonNull SaturationDetail unackedBlocksSaturation,
        @NonNull SaturationDetail unackedBytesSaturation,
        @NonNull SaturationDetail inProgressBlocksSaturation) {
    public static final BlockBufferSaturationInfo NIL = new BlockBufferSaturationInfo(
            Instant.MIN, SaturationDetail.NIL, SaturationDetail.NIL, SaturationDetail.NIL);

    public BlockBufferSaturationInfo {
        requireNonNull(timestamp, "timestamp is required");
        requireNonNull(unackedBlocksSaturation, "unackedBlocksSaturation is required");
        requireNonNull(unackedBytesSaturation, "unackedBytesSaturation is required");
        requireNonNull(inProgressBlocksSaturation, "inProgressBlocksSaturation is required");
    }

    public double maxSaturationPercent() {
        final double d = Double.max(unackedBlocksSaturation.percentage, unackedBytesSaturation.percentage);
        return Double.max(d, inProgressBlocksSaturation.percentage);
    }

    public boolean isSaturated() {
        return unackedBlocksSaturation.isSaturated()
                || unackedBytesSaturation.isSaturated()
                || inProgressBlocksSaturation.isSaturated();
    }

    public boolean isAtActionStage() {
        return unackedBlocksSaturation.isAtActionStage
                || unackedBytesSaturation.isAtActionStage
                || inProgressBlocksSaturation.isAtActionStage;
    }

    /**
     * Detailed information about a buffer saturation facet.
     *
     * @param percentage the saturation as a percentage
     * @param isAtActionStage true if this facet meets the action stage criteria
     * @param current the current raw value associated with this facet
     * @param maxAllowed the current maximum allowed for this facet
     */
    public record SaturationDetail(double percentage, boolean isAtActionStage, long current, long maxAllowed) {
        public static final SaturationDetail NIL = new SaturationDetail(0.0D, false, -1L, -1L);

        public boolean isSaturated() {
            return maxAllowed > 0 && current >= maxAllowed;
        }
    }
}
