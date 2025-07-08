// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.RegularTimeManager;

/**
 * Implementation of {@link TestEnvironment} for tests running on a container network.
 */
public class ContainerTestEnvironment implements TestEnvironment {

    public static final Set<Capability> CAPABILITIES = Set.of(Capability.RECONNECT);

    private final ContainerNetwork network;
    private final RegularTimeManager timeManager = new RegularTimeManager();
    private final ContainerTransactionGenerator transactionGenerator = new ContainerTransactionGenerator();

    /**
     * Constructor for the {@link ContainerTestEnvironment} class.
     */
    public ContainerTestEnvironment() {
        network = new ContainerNetwork(timeManager, transactionGenerator);
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
