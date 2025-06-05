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

    private final TimeManager timeManager = new RegularTimeManager();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network network() {
        throw new UnsupportedOperationException("Not implemented yet!");
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
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() throws InterruptedException {
        throw new UnsupportedOperationException("Not implemented yet!");
    }
}
