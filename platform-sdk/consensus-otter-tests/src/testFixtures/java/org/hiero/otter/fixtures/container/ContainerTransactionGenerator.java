// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * A {@link TransactionGenerator} for the container environment.
 * This class is a placeholder and does not implement any functionality yet.
 */
public class ContainerTransactionGenerator implements TransactionGenerator {

    private static final Logger log = LogManager.getLogger();

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        log.warn("Starting ContainerTransactionGenerator not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        log.warn("Stoping ContainerTransactionGenerator not implemented yet.");
    }
}
