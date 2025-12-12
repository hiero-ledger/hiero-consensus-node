// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model;

import com.hedera.hapi.node.base.Timestamp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Utility class for converting between PBJ and Java types.
 */
public class PbjConverters {

    private PbjConverters() {}

    /**
     * Converts an {@link Instant} to a {@link Timestamp}
     *
     * @param instant the {@code Instant} to convert
     * @return the {@code Timestamp} equivalent of the {@code Instant}
     */
    @Nullable
    public static Timestamp toPbjTimestamp(@Nullable final Instant instant) {
        if (instant == null) {
            return null;
        }
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * Converts a {@link Timestamp} to an {@link Instant}
     *
     * @param timestamp the {@code Timestamp} to convert
     * @return the {@code Instant} equivalent of the {@code Timestamp}
     */
    @Nullable
    public static Instant fromPbjTimestamp(@Nullable final Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }
}
