// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.data.Percentage;

/**
 * Represents a network toxin that can be applied to a proxy to simulate network conditions.
 */
public interface Toxin {

    /** The types of toxins that can be applied to a proxy. */
    enum Type {
        /** A toxin that adds latency to the network traffic. */
        @JsonProperty("latency")
        LATENCY,

        /** A toxin that limits the bandwidth. */
        @JsonProperty("bandwidth")
        BANDWIDTH,
    }

    /** The direction of the toxin (upstream or downstream). */
    enum Stream {
        /** The toxin applies to upstream traffic (client -> server). */
        @JsonProperty("upstream")
        UPSTREAM,

        /** The toxin applies to downstream traffic (server -> client). */
        @JsonProperty("downstream")
        DOWNSTREAM
    }

    /**
     * The name of the toxin.
     *
     * @return the name of the toxin
     */
    @JsonProperty
    @NonNull
    default String name() {
        return type() + "_" + stream();
    }

    /**
     * The type of the toxin.
     *
     * @return the type of the toxin
     */
    @JsonProperty
    @NonNull
    Type type();

    /**
     * The direction of the toxin (upstream or downstream).
     *
     * @return the direction of the toxin
     */
    @JsonProperty
    @NonNull
    default Stream stream() {
        return Stream.UPSTREAM;
    }

    /**
     * The percentage of traffic that is affected by the toxin (0.0-1.0).
     *
     * @return the toxicity of the toxin
     */
    @JsonProperty
    double toxicity();

    /**
     * Additional attributes specific to the type of toxin.
     *
     * @return a map of attribute names to their values
     */
    @JsonProperty
    @NonNull
    Map<String, Long> attributes();

    /**
     * A toxin that adds latency to the network traffic.
     */
    class LatencyToxin implements Toxin {

        private final Duration latency;
        private final Percentage jitter;

        /**
         * Constructs a new LatencyToxin instance.
         *
         * @param latency the amount of latency to add
         * @param jitter the percentage of jitter to apply to the latency
         */
        public LatencyToxin(@NonNull final Duration latency, @NonNull final Percentage jitter) {
            this.latency = requireNonNull(latency);
            this.jitter = requireNonNull(jitter);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Type type() {
            return Type.LATENCY;
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

            final LatencyToxin that = (LatencyToxin) o;
            return latency.equals(that.latency) && jitter.equals(that.jitter);
        }

        @Override
        public int hashCode() {
            return 31 * latency.hashCode() + jitter.hashCode();
        }
    }
}
