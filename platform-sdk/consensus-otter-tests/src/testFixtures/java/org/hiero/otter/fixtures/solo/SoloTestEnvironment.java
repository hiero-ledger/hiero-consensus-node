// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.solo;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.RegularTimeManager;

/**
 * Implementation of {@link TestEnvironment} for tests running on a Solo network.
 */
public class SoloTestEnvironment implements TestEnvironment {

    private final SoloNetwork network;
    private final RegularTimeManager timeManager = new RegularTimeManager();
    private final SoloTransactionGenerator transactionGenerator = new SoloTransactionGenerator();

    /**
     * Constructor for the {@link SoloTestEnvironment} class.
     */
    public SoloTestEnvironment() {
        network = new SoloNetwork(timeManager, transactionGenerator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network network() {
        return network;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() throws InterruptedException {
        network.destroy();
    }
}
