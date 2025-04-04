// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface representing the test environment of an Otter test.
 *
 * <p>This interface provides methods to access various components of the test environment, such as
 * the network, time manager, transaction generator, and validator.
 */
public interface TestEnvironment {

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
    TransactionGenerator generator();

    /**
     * Get the validator associated with this test environment.
     *
     * @return the validator
     */
    @NonNull
    Validator validator();
}
