// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Utilities for dealing with {@link Duration}
 */
public final class DurationUtils {
    private DurationUtils() {}

    /**
     * @param a the first duration to compare
     * @param b the second duration to compare
     * @return true if 'a' is longer than 'b', false if 'a' is shorter or equal
     */
    public static boolean isLonger(@NonNull final Duration a, @NonNull final Duration b) {
        return a.compareTo(b) > 0;
    }

    /**
     * @param a the first duration to compare
     * @param b the second duration to compare
     * @return true if 'a' is shorter than 'b', false if 'a' is shorter or equal
     */
    public static boolean isShorter(@NonNull final Duration a, @NonNull final Duration b) {
        return a.compareTo(b) < 0;
    }

    /**
     * @param a the first duration
     * @param b the second duration
     * @return the longer of the two durations
     */
    public static Duration max(@NonNull final Duration a, @NonNull final Duration b) {
        return isLonger(a, b) ? a : b;
    }
}
