// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.context.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple uncaught exception handler that logs the exception.
 */
public class PlatformUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final Logger logger = LogManager.getLogger(PlatformUncaughtExceptionHandler.class);

    @Override
    public void uncaughtException(final @NonNull Thread t, final @NonNull Throwable e) {
        logger.error("Uncaught exception in thread: {}", t.getName(), e);
    }
}
