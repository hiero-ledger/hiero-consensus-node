// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.level;

import com.swirlds.logging.api.Level;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Enumeration representing logging configuration levels.
 * These levels define the granularity of logging, ranging from OFF (no logging) to TRACE (most detailed logging).
 * <p>
 * Note: The UNDEFINED level is intended for internal use and should not be exposed in public APIs.
 */
public enum ConfigLevel {
    UNDEFINED(null),
    OFF(Level.OFF),
    ERROR(Level.ERROR),
    WARN(Level.WARN),
    INFO(Level.INFO),
    DEBUG(Level.DEBUG),
    TRACE(Level.TRACE);

    final Level level;

    public @NonNull Level level() {
        return level;
    }

    ConfigLevel(final Level level) {
        this.level = level;
    }
}
