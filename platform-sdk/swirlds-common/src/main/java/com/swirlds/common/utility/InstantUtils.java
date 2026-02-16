// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

public class InstantUtils {

    public static final long MICROS_IN_SECOND = 1_000_000L;
    public static final long NANOS_IN_MICRO = 1_000L;

    private InstantUtils() {
        // Utility class
    }

    /**
     * Converts an Instant to epoch microseconds.
     */
    public static long instantToMicros(@NonNull final Instant instant) {
        return instant.getEpochSecond() * MICROS_IN_SECOND + instant.getNano() / NANOS_IN_MICRO;
    }

    /**
     * Converts epoch microseconds to an Instant.
     */
    @NonNull
    public static Instant microsToInstant(final long micros) {
        final long seconds = micros / MICROS_IN_SECOND;
        final int nanos = (int) ((micros % MICROS_IN_SECOND) * NANOS_IN_MICRO);
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
