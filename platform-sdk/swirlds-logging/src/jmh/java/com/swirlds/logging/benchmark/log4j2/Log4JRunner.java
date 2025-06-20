// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.log4j2;

import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.util.Throwables;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;

/**
 * A Runner that does a bunch of operations with Log4j
 */
public class Log4JRunner implements Runnable {

    private final Logger logger;

    private final Marker marker1 = MarkerManager.getMarker("marker");

    @SuppressWarnings("deprecation")
    private final Marker marker2 = MarkerManager.getMarker("marker2", marker1);

    public Log4JRunner(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void run() {
        logger.log(Level.INFO, "L0, Hello world!");
        logger.log(Level.INFO, "L1, A quick brown fox jumps over the lazy dog.");
        logger.log(Level.INFO, "L2, Hello world!", Throwables.THROWABLE);
        logger.log(Level.INFO, "L3, Hello {}!", "placeholder");

        ThreadContext.put("key", "value");
        logger.log(Level.INFO, "L4, Hello world!");
        ThreadContext.clearAll();

        logger.log(Level.INFO, marker1, "L5, Hello world!");

        ThreadContext.put("user-id", Constants.USER_1);
        logger.log(Level.INFO, "L6, Hello world!");

        ThreadContext.put("user-id", Constants.USER_2);
        logger.log(Level.INFO, "L7, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);

        ThreadContext.put("key", "value");
        ThreadContext.put("user-id", Constants.USER_3);
        logger.log(Level.INFO, "L8, Hello world!");

        logger.log(Level.INFO, marker1, "L9, Hello world!");
        logger.log(Level.INFO, marker2, "L10, Hello world!");

        ThreadContext.put("key", "value");
        logger.log(Level.INFO, marker2, "L11, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);

        logger.log(Level.INFO, "L12, Hello world!", Throwables.DEEP_THROWABLE);
    }
}
