// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.network.utils.BandwidthLimit;

/**
 * Represents a network toxin that can be applied to a proxy to simulate network conditions.
 */
public abstract class Toxin {

    /** The types of toxins that can be applied to a proxy. */
    public enum Type {
        /** A toxin that adds latency to the network traffic. */
        @JsonProperty("latency")
        LATENCY,

        /** A toxin that limits the bandwidth. */
        @JsonProperty("bandwidth")
        BANDWIDTH,
    }

    /** The direction of the toxin (upstream or downstream). */
    public enum Stream {
        /** The toxin applies to upstream traffic (client -> server). */
        @JsonProperty("upstream")
        UPSTREAM,

        /** The toxin applies to downstream traffic (server -> client). */
        @JsonProperty("downstream")
        DOWNSTREAM
    }

    protected final Stream stream;

    protected Toxin(@NonNull final Stream stream) {
        this.stream = stream;
    }

    /**
     * The name of the toxin.
     *
     * @return the name of the toxin
     */
    @JsonProperty
    @NonNull
    public String name() {
        return type() + "_" + stream();
    }

    /**
     * The type of the toxin.
     *
     * @return the type of the toxin
     */
    @JsonProperty
    @NonNull
    public abstract Type type();

    /**
     * The direction of the toxin (upstream or downstream).
     *
     * @return the direction of the toxin
     */
    @JsonProperty
    @NonNull
    public Stream stream() {
        return stream;
    }

    /**
     * The percentage of traffic that is affected by the toxin (0.0-1.0).
     *
     * @return the toxicity of the toxin
     */
    @JsonProperty
    public abstract double toxicity();

    /**
     * Additional attributes specific to the type of toxin.
     *
     * @return a map of attribute names to their values
     */
    @JsonProperty
    @NonNull
    public abstract Map<String, Integer> attributes();

    /**
     * Creates a new toxin with the same configuration but applied in the opposite direction.
     *
     * @return the downstream toxin
     */
    @JsonIgnore
    @NonNull
    public abstract Toxin downstream();

    /**
     * A toxin that adds latency to the network traffic.
     */
    public static class LatencyToxin extends Toxin {

        private final Duration latency;
        private final Percentage jitter;

        /**
         * Constructs a new LatencyToxin instance.
         *
         * @param latency the amount of latency to add
         * @param jitter the percentage of jitter to apply to the latency
         */
        public LatencyToxin(@NonNull final Duration latency, @NonNull final Percentage jitter) {
            this(latency, jitter, Stream.UPSTREAM);
        }

        private LatencyToxin(
                @NonNull final Duration latency, @NonNull final Percentage jitter, @NonNull final Stream stream) {
            super(stream);
            this.latency = requireNonNull(latency);
            this.jitter = requireNonNull(jitter);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
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
        public Map<String, Integer> attributes() {
            return Map.of(
                    "latency", (int) latency.toMillis(), "jitter", (int) (latency.toMillis() * jitter.value * 0.01));
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public LatencyToxin downstream() {
            return new LatencyToxin(latency, jitter, Stream.DOWNSTREAM);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final LatencyToxin that = (LatencyToxin) o;
            return latency.equals(that.latency) && jitter.equals(that.jitter) && stream == that.stream();
        }

        @Override
        public int hashCode() {
            int result = latency.hashCode();
            result = 31 * result + jitter.hashCode();
            result = 31 * result + stream.hashCode();
            return result;
        }
    }

    /**
     *  * A toxin that limits the bandwidth.
     */
    public static class BandwidthToxin extends Toxin {

        private final BandwidthLimit bandwidthLimit;

        /**
         * Constructs a new BandwidthToxin instance.
         *
         * @param bandwidthLimit the bandwidth limit to apply
         */
        protected BandwidthToxin(@NonNull final BandwidthLimit bandwidthLimit) {
            this(bandwidthLimit, Stream.UPSTREAM);
        }

        private BandwidthToxin(@NonNull final BandwidthLimit bandwidthLimit, @NonNull final Stream stream) {
            super(stream);
            this.bandwidthLimit = requireNonNull(bandwidthLimit);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Type type() {
            return Type.BANDWIDTH;
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
        public Map<String, Integer> attributes() {
            return Map.of("rate", bandwidthLimit.toKilobytesPerSecond());
        }

        @NonNull
        @Override
        public BandwidthToxin downstream() {
            return new BandwidthToxin(bandwidthLimit, Stream.DOWNSTREAM);
        }
    }
}
