// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.swirldslog;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.benchmark.config.Constants;
import com.swirlds.logging.benchmark.util.Throwables;

/**
 * A Runner that does a bunch of operations with Swirlds-logging-framework
 */
public class SwirldsLogRunner implements Runnable {

    private final Logger logger;

    public SwirldsLogRunner(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void run() {
        logger.log(Level.INFO, "L0, Hello world!");
        logger.log(Level.INFO, "L1, A quick brown fox jumps over the lazy dog.");
        logger.log(Level.INFO, "L2, Hello world!", Throwables.THROWABLE);
        logger.log(Level.INFO, "L3, Hello {}!", "placeholder");
        logger.withContext("key", "value").log(Level.INFO, "L4, Hello world!");
        logger.withMarker("marker").log(Level.INFO, "L5, Hello world!");
        logger.withContext("user-id", Constants.USER_1).log(Level.INFO, "L6, Hello world!");
        logger.withContext("user-id", Constants.USER_2)
                .log(Level.INFO, "L7, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);
        logger.withContext("user-id", Constants.USER_3)
                .withContext("key", "value")
                .log(Level.INFO, "L8, Hello world!");
        logger.withMarker("marker").log(Level.INFO, "L9, Hello world!");
        logger.withMarker("marker1").withMarker("marker2").log(Level.INFO, "L10, Hello world!");
        logger.withContext("key", "value")
                .withMarker("marker1")
                .withMarker("marker2")
                .log(Level.INFO, "L11, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);
        logger.log(Level.INFO, "L12, Hello world!", Throwables.DEEP_THROWABLE);
    }
}
