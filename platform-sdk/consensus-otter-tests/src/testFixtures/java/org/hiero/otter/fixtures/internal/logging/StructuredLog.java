// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;

/**
 * A structured representation of a log event captured with the {@link InMemoryAppender}
 *
 * @param level       The severity level of the log message.
 * @param message     The formatted log message.
 * @param loggerName  The name of the logger that produced the event.
 * @param threadName  The name of the thread that generated the log.
 * @param marker      An optional marker associated with the log event.
 *
 * @see InMemoryAppender
 */
public record StructuredLog(
        @NonNull Level level,
        @NonNull String message,
        @NonNull String loggerName,
        @NonNull String threadName,
        @Nullable Marker marker,
        @Nullable String nodeId) {}
