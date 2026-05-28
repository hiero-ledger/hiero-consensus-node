// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import java.time.Duration;
import java.util.Formatter;
import org.jspecify.annotations.NonNull;

/**
 * Represents the results of a network simulation run.
 * @param nodes amount of nodes for which
 * @param averageC2C average C2C time
 * @param maxC2C maximum C2C time
 * @param eventsPerSec events per second
 * @param bytesPerSec bytes per second
 */
public record SimulationResult(int nodes, Duration averageC2C, Duration maxC2C, long eventsPerSec, long bytesPerSec) {

    @Override
    public @NonNull String toString() {
        final StringBuilder sb = new StringBuilder();
        final Formatter fmt = new Formatter(sb);
        fmt.format("Num nodes:    %d%n", nodes);
        fmt.format("Avg C2C:      %s%n", averageC2C);
        fmt.format("Max C2C:      %s%n", maxC2C);
        fmt.format("Ev/sec:       %,d%n", eventsPerSec);
        fmt.format("Bytes/sec:    %,d%n", bytesPerSec);
        return sb.toString();
    }
}
