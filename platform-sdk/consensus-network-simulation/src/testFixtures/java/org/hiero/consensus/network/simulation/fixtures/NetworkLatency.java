// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

public class NetworkLatency {

    private final long[][] latenciesMicros;

    private NetworkLatency(final long[][] latenciesMicros) {
        this.latenciesMicros = latenciesMicros;
    }

    public Duration getLatency(final int node1Index, final int node2Index) {
        return Duration.of(latenciesMicros[node1Index][node2Index], ChronoUnit.MICROS);
    }

    /**
     * Configures connection latencies from a 2D array of millisecond values. The value at {@code latenciesMs[i][j]} is
     * the latency in milliseconds from node {@code i} to node {@code j}. The array must be square with dimensions equal
     * to the number of nodes.
     *
     * @param latenciesMs a square matrix of latencies in milliseconds, where {@code latenciesMs[i][j]} is the time in
     *                    milliseconds for an event sent from node {@code i} to reach node {@code j}
     */
    public static NetworkLatency pingMatrix(@NonNull final int[][] latenciesMs) {
        // Validate that the matrix is square and has non-negative latencies
        final int numNodes = latenciesMs.length;
        final long[][] latenciesMicros = new long[numNodes][];
        for (int i = 0; i < latenciesMs.length; i++) {
            final int[] row = latenciesMs[i];
            if (row.length != numNodes) {
                throw new IllegalArgumentException("Latency matrix must be square");
            }
            for (final int latency : row) {
                if (latency < 0) {
                    throw new IllegalArgumentException("Latencies must be non-negative");
                }
            }
            // we multiply by 1000 to convert to micros
            // we also divide by 2 to convert from 2-way latency (ping) to 1-way latency
            latenciesMicros[i] = Arrays.stream(row)
                    .mapToLong(latency -> (latency * 1000L) / 2)
                    .toArray();
        }
        return new NetworkLatency(latenciesMicros);
    }

    public static NetworkLatency uniformLatency(@NonNull final Duration latency, final int numNodes) {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("Latency must be non-negative");
        }
        final long latencyMicros = toMicros(latency);
        final long[][] latenciesMicros = new long[numNodes][numNodes];
        for (int i = 0; i < numNodes; i++) {
            for (int j = 0; j < numNodes; j++) {
                latenciesMicros[i][j] = latencyMicros;
            }
        }
        return new NetworkLatency(latenciesMicros);
    }

    private static long toMicros(final Duration d) {
        return d.getSeconds() * 1_000_000L + d.getNano() / 1_000L;
    }
}
