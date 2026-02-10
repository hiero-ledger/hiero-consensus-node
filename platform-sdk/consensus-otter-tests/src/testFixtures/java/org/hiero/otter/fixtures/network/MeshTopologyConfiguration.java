// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;

/**
 * Configuration for a mesh network topology where all nodes are fully connected.
 *
 * <p>Users can configure the latency, jitter, and bandwidth characteristics that apply to all
 * connections in the mesh topology.
 */
@SuppressWarnings("unused")
public record MeshTopologyConfiguration(
        @NonNull Duration averageLatency, @NonNull Percentage jitter, @NonNull BandwidthLimit bandwidth)
        implements TopologyConfiguration {

    /**
     * Default configuration with 200ms average latency, 5% jitter, and unlimited bandwidth.
     */
    public static final MeshTopologyConfiguration DEFAULT =
            new MeshTopologyConfiguration(Duration.ofMillis(200), Percentage.withPercentage(5), UNLIMITED_BANDWIDTH);

    /**
     * Configuration with zero latency, zero jitter, and unlimited bandwidth.
     */
    public static final MeshTopologyConfiguration ZERO_LATENCY =
            new MeshTopologyConfiguration(Duration.ZERO, Percentage.withPercentage(0), UNLIMITED_BANDWIDTH);

    /**
     * Creates a MeshTopologyConfiguration with specified parameters.
     *
     * @param averageLatency the average latency for connections
     * @param jitter the jitter percentage for connections
     * @param bandwidth the bandwidth limit for connections
     * @throws NullPointerException if any of the parameters are {@code null}
     * @throws IllegalArgumentException if latency is negative
     */
    public MeshTopologyConfiguration {
        requireNonNull(averageLatency);
        requireNonNull(jitter);
        requireNonNull(bandwidth);

        if (averageLatency.isNegative()) {
            throw new IllegalArgumentException("Average latency must not be negative");
        }
    }

    /**
     * Creates a copy of this {@code MeshTopologyConfiguration} with the specified average latency.
     *
     * @param latency the average latency for connections
     * @return a new {@code MeshTopologyConfiguration} with the specified latency
     * @throws NullPointerException if {@code latency} is {@code null}
     * @throws IllegalArgumentException if latency is negative
     */
    @NonNull
    public MeshTopologyConfiguration withAverageLatency(@NonNull final Duration latency) {
        return new MeshTopologyConfiguration(latency, jitter, bandwidth);
    }

    /**
     * Creates a copy of this {@code MeshTopologyConfiguration} with the specified jitter.
     *
     * @param jitterPercent the jitter percentage for connections
     * @return a new {@code MeshTopologyConfiguration} with the specified jitter
     * @throws NullPointerException if {@code jitterPercent} is {@code null}
     */
    @NonNull
    public MeshTopologyConfiguration withJitter(@NonNull final Percentage jitterPercent) {
        return new MeshTopologyConfiguration(averageLatency, jitterPercent, bandwidth);
    }

    /**
     * Creates a copy of this {@code MeshTopologyConfiguration} with the specified bandwidth limit.
     *
     * @param bandwidthLimit the bandwidth limit for connections
     * @return a new {@code MeshTopologyConfiguration} with the specified bandwidth limit
     * @throws NullPointerException if {@code bandwidthLimit} is {@code null}
     */
    @NonNull
    public MeshTopologyConfiguration withBandwidth(@NonNull final BandwidthLimit bandwidthLimit) {
        return new MeshTopologyConfiguration(averageLatency, jitter, bandwidthLimit);
    }
}
