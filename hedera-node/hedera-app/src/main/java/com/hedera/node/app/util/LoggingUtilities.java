// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

public class LoggingUtilities {

    private LoggingUtilities() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Helper method to format current thread information for logging.
     * @return formatted string with thread name and ID
     */
    public static String threadInfo() {
        final Thread currentThread = Thread.currentThread();
        return String.format("[Thread:%s/ID:%d]", currentThread.getName(), currentThread.threadId());
    }

    /**
     * Helper method to log messages with context about the current thread and block-node connection.
     * @param logger the logger to use
     * @param level the log level
     * @param connection the block-node connection context
     * @param message the log message
     * @param args optional arguments for the log message
     */
    public static void logWithContext(
            Logger logger, Level level, BlockNodeConnection connection, String message, Object... args) {
        if (logger.isEnabled(level)) {
            message = formatLogMessage(message, connection);
            logger.atLevel(level).log(message, args);
        }
    }

    /**
     * Helper method to log messages with context about the current thread.
     * @param logger the logger to use
     * @param level the log level
     * @param message the log message
     * @param args optional arguments for the log message
     */
    public static void logWithContext(Logger logger, Level level, String message, Object... args) {
        if (logger.isEnabled(level)) {
            message = String.format("%s %s", LoggingUtilities.threadInfo(), message);
            logger.atLevel(level).log(message, args);
        }
    }

    /**
     * Helper method to format log messages with thread and connection context.
     * @param message the log message
     * @param connection the block-node connection context
     * @return the formatted log message
     */
    public static String formatLogMessage(String message, BlockNodeConnection connection) {
        return String.format("%s %s %s", LoggingUtilities.threadInfo(), connection, message);
    }
}
