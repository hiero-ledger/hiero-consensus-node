// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.sloth.fixtures.container.proto.LogEntry;
import org.hiero.sloth.fixtures.logging.StructuredLog;

/**
 * Converts between the classes of the test framework and the messages that a node exchanges with the test framework.
 */
public class ProtobufConverter {
    private ProtobufConverter() {}

    /**
     * Converts a LogEntry to a StructuredLog.
     *
     * @param sourceLog the LogEntry to convert
     * @return the converted StructuredLog
     */
    @NonNull
    public static StructuredLog toPlatform(@NonNull final LogEntry sourceLog) {
        return new StructuredLog(
                sourceLog.timestamp(),
                Level.toLevel(sourceLog.level()),
                sourceLog.message(),
                sourceLog.loggerName(),
                sourceLog.thread(),
                MarkerManager.getMarker(sourceLog.marker()),
                sourceLog.nodeId() < 0 ? null : NodeId.of(sourceLog.nodeId()));
    }

    /**
     * Converts a StructuredLog to a LogEntry.
     *
     * @param sourceLog the StructuredLog to convert
     * @return the converted LogEntry
     */
    @NonNull
    public static LogEntry fromPlatform(@NonNull final StructuredLog sourceLog) {
        return LogEntry.newBuilder()
                .timestamp(sourceLog.timestamp())
                .level(sourceLog.level().toString())
                .loggerName(sourceLog.loggerName())
                .thread(sourceLog.threadName())
                .message(sourceLog.message())
                .marker(sourceLog.marker() != null ? sourceLog.marker().toString() : "")
                .nodeId(sourceLog.nodeId() != null ? sourceLog.nodeId().id() : -1L)
                .build();
    }
}
