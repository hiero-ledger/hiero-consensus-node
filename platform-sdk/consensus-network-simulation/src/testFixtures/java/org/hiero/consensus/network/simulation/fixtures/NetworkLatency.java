package org.hiero.consensus.network.simulation.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

public class NetworkLatency {

    final int[][] latenciesMs;

    private NetworkLatency(final int[][] latenciesMs) {
        this.latenciesMs = latenciesMs;
    }

    public Duration getLatency(final int node1Index, final int node2Index) {
        return Duration.ofMillis(latenciesMs[node1Index][node2Index]);
    }

    /**
     * Configures connection latencies from a 2D array of millisecond values. The value at {@code latenciesMs[i][j]} is
     * the latency in milliseconds from node {@code i} to node {@code j}. The array must be square with dimensions equal
     * to the number of nodes.
     *
     * @param latenciesMs a square matrix of latencies in milliseconds, where {@code latenciesMs[i][j]} is the time in
     *                    milliseconds for an event sent from node {@code i} to reach node {@code j}
     */
    public static NetworkLatency fromMatrix(@NonNull final int[][] latenciesMs) {
        // Validate that the matrix is square and has non-negative latencies
        final int numNodes = latenciesMs.length;
        for (final int[] row : latenciesMs) {
            if (row.length != numNodes) {
                throw new IllegalArgumentException("Latency matrix must be square");
            }
            for (final int latency : row) {
                if (latency < 0) {
                    throw new IllegalArgumentException("Latencies must be non-negative");
                }
            }
        }
        return new NetworkLatency(latenciesMs);
    }

    public static NetworkLatency uniformLatency(@NonNull final Duration latency, final int numNodes) {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("Latency must be non-negative");
        }
        final int latencyMs = (int) latency.toMillis();
        final int[][] latenciesMs = new int[numNodes][numNodes];
        for (int i = 0; i < numNodes; i++) {
            for (int j = 0; j < numNodes; j++) {
                latenciesMs[i][j] = latencyMs;
            }
        }
        return new NetworkLatency(latenciesMs);
    }
}
