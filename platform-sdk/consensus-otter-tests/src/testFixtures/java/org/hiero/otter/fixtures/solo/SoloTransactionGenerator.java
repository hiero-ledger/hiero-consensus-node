package org.hiero.otter.fixtures.solo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * A {@link TransactionGenerator} for the Solo environment.
 * This class is a placeholder and does not implement any functionality yet.
 */
public class SoloTransactionGenerator implements TransactionGenerator {

    private static final Logger log = LogManager.getLogger();

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        log.info("SoloTransactionGenerator not implemented yet! Doing nothing...");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        log.info("SoloTransactionGenerator not implemented yet!");
    }
}
