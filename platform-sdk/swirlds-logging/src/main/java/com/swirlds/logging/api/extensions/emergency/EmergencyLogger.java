// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.emergency;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.event.LogEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * This interface is used to log emergency events. It is used in the logger api to log events. An implementation must
 * not be dependent on any other logging system and must be directly usable in the logger api. Next to that an
 * implementation must be bullet proof and must not throw any exceptions.
 */
public interface EmergencyLogger {

    /**
     * Logs a null pointer exception.
     *
     * @param nameOfNullParam the name of the null parameter
     */
    void logNPE(@NonNull String nameOfNullParam);

    /**
     * Logs an event.
     *
     * @param event the event to log
     */
    void log(@NonNull LogEvent event);

    /**
     * Logs a message.
     *
     * @param level   the level of the message
     * @param message the message to log
     */
    default void log(@NonNull final Level level, @NonNull final String message) {
        log(level, message, null);
    }

    /**
     * Logs a message with a throwable.
     *
     * @param level   the level of the message
     * @param message the message to log
     * @param thrown  the throwable to log
     */
    void log(@NonNull Level level, @NonNull String message, @Nullable Throwable thrown);
}
