// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated.config;

import com.swirlds.platform.test.fixtures.simulated.Latency;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;

/**
 * A builder for {@link NodeConfig}
 */
public class NodeConfigBuilder {
    private Duration createEventEvery = Duration.ofMillis(20);
    private Latency customLatency = new Latency(Duration.ZERO);
    private Duration intakeQueueDelay = Duration.ZERO;

    private NodeConfigBuilder() {}

    /**
     * Creates and returns a new builder with the default configuration.
     *
     * @return the new builder
     */
    public static @NonNull NodeConfigBuilder builder() {
        return new NodeConfigBuilder();
    }

    /**
     * Creates and returns a new builder with the provided configuration.
     *
     * @return the new builder
     */
    public static @NonNull NodeConfigBuilder builder(@NonNull final NodeConfig config) {
        final NodeConfigBuilder builder = builder();
        builder.setCustomLatency(config.customLatency());
        builder.setCreateEventEvery(config.createEventEvery());
        builder.setIntakeQueueDelay(config.intakeQueueDelay());
        return builder;
    }

    /**
     * Sets the interval at which events will be created.
     *
     * @param createEventEvery the event creation interval
     * @return {@code this}
     */
    public @NonNull NodeConfigBuilder setCreateEventEvery(@NonNull final Duration createEventEvery) {
        this.createEventEvery = Objects.requireNonNull(createEventEvery);
        return this;
    }

    /**
     * Sets the latency of this node. A single round trip if this latency plus the latency of the peer.
     *
     * @param customLatency the latency of this node
     * @return {@code this}
     */
    public @NonNull NodeConfigBuilder setCustomLatency(@NonNull final Latency customLatency) {
        this.customLatency = Objects.requireNonNull(customLatency);
        return this;
    }

    /**
     * Sets the intake queue delay. Events in the queue will wait this long until being handled.
     *
     * @param intakeQueueDelay the intake queue delay
     * @return {@code this}
     */
    public @NonNull NodeConfigBuilder setIntakeQueueDelay(@NonNull final Duration intakeQueueDelay) {
        this.intakeQueueDelay = Objects.requireNonNull(intakeQueueDelay);
        return this;
    }

    /**
     * Builds the node configuration.
     *
     * @return the node configuration
     */
    public @NonNull NodeConfig build() {
        return new NodeConfig(createEventEvery, customLatency, intakeQueueDelay);
    }
}
