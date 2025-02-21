// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.event;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A consumer that consumes log events.
 */
public interface LogEventConsumer {

    /**
     * Checks if the consumer is enabled for the given name and level.
     *
     * @param name  the name
     * @param level the level
     * @return true if the consumer is enabled, false otherwise
     */
    default boolean isEnabled(@NonNull String name, @NonNull Level level, @Nullable Marker marker) {
        return true;
    }

    void accept(@NonNull LogEvent event);
}
