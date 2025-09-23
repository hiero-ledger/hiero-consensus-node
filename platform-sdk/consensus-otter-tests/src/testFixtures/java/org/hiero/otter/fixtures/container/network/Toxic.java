// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.data.Percentage;

/**
 * Represents a network toxic that can be applied to a proxy to simulate network conditions.
 */
public interface Toxic {

    /** The types of toxics that can be applied to a proxy. */
    enum ToxicType {
        /** A toxic that adds latency to the network traffic. */
        @JsonProperty("latency")
        LATENCY,

        /** A toxic that limits the bandwidth. */
        @JsonProperty("bandwidth")
        BANDWIDTH,
    }

    /** The direction of the toxic (upstream or downstream). */
    enum ToxicStream {
        /** The toxic applies to upstream traffic (client -> server). */
        @JsonProperty("upstream")
        UPSTREAM,

        /** The toxic applies to downstream traffic (server -> client). */
        @JsonProperty("downstream")
        DOWNSTREAM
    }

    /**
     * The name of the toxic.
     *
     * @return the name of the toxic
     */
    @JsonProperty
    @NonNull
    default String name() {
        return type() + "_" + stream();
    }

    /**
     * The type of the toxic.
     *
     * @return the type of the toxic
     */
    @JsonProperty
    @NonNull
    ToxicType type();

    /**
     * The direction of the toxic (upstream or downstream).
     *
     * @return the direction of the toxic
     */
    @JsonProperty
    @NonNull
    default ToxicStream stream() {
        return ToxicStream.UPSTREAM;
    }

    /**
     * The percentage of traffic that is affected by the toxic (0.0-1.0).
     *
     * @return the toxicity of the toxic
     */
    @JsonProperty
    double toxicity();

    /**
     * Additional attributes specific to the type of toxic.
     *
     * @return a map of attribute names to their values
     */
    @JsonProperty
    @NonNull
    Map<String, Long> attributes();

    /**
     * A toxic that adds latency to the network traffic.
     */
    class LatencyToxic implements Toxic {

        private final Duration latency;
        private final Percentage jitter;

        /**
         * Constructs a new LatencyToxic instance.
         *
         * @param latency the amount of latency to add
         * @param jitter the percentage of jitter to apply to the latency
         */
        public LatencyToxic(@NonNull final Duration latency, @NonNull final Percentage jitter) {
            this.latency = requireNonNull(latency);
            this.jitter = requireNonNull(jitter);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public ToxicType type() {
            return ToxicType.LATENCY;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public double toxicity() {
            return 1.0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Map<String, Long> attributes() {
            return Map.of("latency", latency.toMillis(), "jitter", (long) (latency.toMillis() * jitter.value * 0.01));
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final LatencyToxic that = (LatencyToxic) o;
            return latency.equals(that.latency) && jitter.equals(that.jitter);
        }

        @Override
        public int hashCode() {
            return 31 * latency.hashCode() + jitter.hashCode();
        }
    }
}
