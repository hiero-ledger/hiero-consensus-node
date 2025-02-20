// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.json;

import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.logging.legacy.LogMarkerType;
import java.util.function.Predicate;

/**
 * Check if the log marker signifies an error. Allows all entries with a {@link LogMarkerType#ERROR} type to pass.
 */
public class HasErrorMarkerFilter implements Predicate<JsonLogEntry> {

    public static HasErrorMarkerFilter hasErrorMarker() {
        return new HasErrorMarkerFilter();
    }

    public HasErrorMarkerFilter() {}

    @Override
    public boolean test(JsonLogEntry jsonLogEntry) {
        String markerName = jsonLogEntry.getMarker();
        try {
            return LogMarker.valueOf(markerName).getType().equals(LogMarkerType.ERROR);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
