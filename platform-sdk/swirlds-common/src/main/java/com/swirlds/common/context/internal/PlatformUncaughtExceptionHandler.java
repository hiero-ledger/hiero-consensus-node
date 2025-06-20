// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.context.internal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple uncaught exception handler that logs the exception and rethrows it.
 */
public class PlatformUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final Logger logger = LogManager.getLogger(PlatformUncaughtExceptionHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        logger.error("Uncaught exception in thread: " + t.getName(), e);
        throw new RuntimeException("Uncaught exception", e);
    }
}
