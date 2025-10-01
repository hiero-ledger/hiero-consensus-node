// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

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
}
