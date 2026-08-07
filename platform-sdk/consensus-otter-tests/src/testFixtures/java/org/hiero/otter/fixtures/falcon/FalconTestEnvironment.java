// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Set;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;

public class FalconTestEnvironment implements TestEnvironment {

    public FalconTestEnvironment(final long randomSeed) {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Capability> capabilities() {
        return Set.of(Capability.DETERMINISTIC_EXECUTION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network network() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TimeManager timeManager() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TransactionGenerator transactionGenerator() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ChaosBot createChaosBot(@NonNull final ChaosBotConfiguration configuration) {
        throw new UnsupportedOperationException("ChaosBot is not supported in FalconTestEnvironment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Path outputDirectory() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {}
}
