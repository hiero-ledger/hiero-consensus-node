// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * A chaos bot that introduces randomized faults into the network.
 */
public interface ChaosBot {

    /**
     * Run chaos experiments for the specified duration.
     *
     * @param duration the duration to run chaos experiments
     */
    void runChaos(@NonNull Duration duration);
}
