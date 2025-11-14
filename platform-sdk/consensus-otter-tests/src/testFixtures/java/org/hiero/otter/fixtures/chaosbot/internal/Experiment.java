// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.otter.fixtures.Network;

/**
 * An experiment that modifies the network or individual nodes in some way for a limited time.
 */
public abstract class Experiment {

    protected final Network network;
    protected final Instant endTime;

    /**
     * Create a new experiment.
     *
     * @param network the network of the test environment
     * @param endTime the moment this experiment should end
     */
    protected Experiment(@NonNull final Network network, @NonNull final Instant endTime) {
        this.network = requireNonNull(network);
        this.endTime = requireNonNull(endTime);
    }

    /**
     * The moment this experiment should end.
     *
     * @return the end time of the experiment
     */
    @NonNull
    public Instant endTime() {
        return endTime;
    }

    /**
     * End the experiment, reverting any changes.
     */
    public abstract void end();
}
