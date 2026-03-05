// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Set;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;

/**
 * Interface representing the test environment of an Otter test.
 *
 * <p>This interface provides methods to access various components of the test environment, such as
 * the network, time manager, transaction generator, and validator.
 */
public interface TestEnvironment {

    /**
     * Get the capabilities supported by this test environment.
     *
     * @return the set of capabilities
     */
    @NonNull
    Set<Capability> capabilities();

    /**
     * Get the network associated with this test environment.
     *
     * @return the network
     */
    @NonNull
    Network network();

    /**
     * Get the time manager associated with this test environment.
     *
     * @return the time manager
     */
    @NonNull
    TimeManager timeManager();

    /**
     * Get the transaction generator associated with this test environment.
     *
     * @return the transaction generator
     */
    @NonNull
    TransactionGenerator transactionGenerator();

    /**
     * Create a chaos bot that can introduce randomized faults into the test environment.
     *
     * @param configuration the chaos bot configuration
     * @return the chaos bot
     */
    @NonNull
    ChaosBot createChaosBot(@NonNull ChaosBotConfiguration configuration);

    /**
     * Get the root output directory for this test environment.
     * @return the output directory
     */
    @NonNull
    Path outputDirectory();

    /**
     * Destroys the test environment. Once this method is called, the test environment and all its
     * components are no longer usable. This method is idempotent, meaning that it is safe to call
     * multiple times.
     */
    void destroy();
}
