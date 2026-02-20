// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Network;

/**
 * An experiment that can be executed by a chaos bot.
 */
public interface Experiment {

    /**
     * The name of the experiment.
     *
     * @return the name of the experiment
     */
    @NonNull
    String name();

    /**
     * Starts the experiment and returns the remaining steps.
     *
     * <p>If the experiment cannot be started (e.g., no suitable target nodes are available), an empty list should be
     * returned.
     *
     * @param network the network to run the experiment on
     * @param now the current time
     * @param randotron the random number generator to use
     * @return the remaining steps of the experiment
     */
    @NonNull
    List<Step> start(@NonNull Network network, @NonNull Instant now, @NonNull Randotron randotron);

    /**
     * A step in an experiment.
     *
     * @param timestamp the time at which to execute the action
     * @param action the action to execute
     */
    record Step(@NonNull Instant timestamp, @NonNull Runnable action) {

        /**
         * Create a new step.
         *
         * @param timestamp the time at which to execute the action
         * @param action the action to execute
         */
        public Step {
            requireNonNull(timestamp);
            requireNonNull(action);
        }
    }
}
