// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * Factory for creating random experiments.
 */
public class ExperimentFactory {

    private final TestEnvironment env;
    private final Randotron randotron;

    /**
     * Create a new experiment factory.
     *
     * @param env the test environment
     * @param randotron the random number generator
     */
    public ExperimentFactory(@NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        this.env = requireNonNull(env);
        this.randotron = requireNonNull(randotron);
    }

    /**
     * Create a new random experiment.
     *
     * @return the created experiment, or {@code null} if no suitable experiment could be created
     */
    @Nullable
    public Experiment createExperiment() {
        // For now, we assume all experiments are equally likely. Will become configurable in the future.
        final int experimentType = randotron.nextInt(5);
        return switch (experimentType) {
            case 0 -> HighLatencyNodeExperiment.create(env, randotron);
            case 1 -> LowBandwidthNodeExperiment.create(env, randotron);
            case 2 -> NetworkPartitionExperiment.create(env, randotron);
            case 3 -> NodeFailureExperiment.create(env, randotron);
            case 4 -> NodeIsolationExperiment.create(env, randotron);
            default -> throw new IllegalStateException("Unreachable code reached in ExperimentFactory");
        };
    }
}
