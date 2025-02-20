// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/** Utility for creating human-readable names deterministically */
public final class NameUtils {
    private NameUtils() {}

    private static final String[] NAMES = {
        "Austin", "Bob", "Cody", "Dave", "Edward", "Fred", "Gina", "Hank", "Iris", "Judy", "Kelly",
        "Lazar", "Mike", "Nina", "Olie", "Pete", "Quin", "Rita", "Susi", "Tina", "Ursa", "Vera",
        "Will", "Xeno", "York", "Zeke"
    };

    /**
     * Return a human-readable and deterministic name based on a long value
     *
     * @param value the value to base the name on
     * @return a human-readable name
     */
    @NonNull
    public static String getName(final long value) {
        return NAMES[((int) value) % NAMES.length] + (value >= NAMES.length ? ("-" + String.format("%x", value)) : "");
    }
}
