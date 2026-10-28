// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;

/**
 * What {@link FalconRecorder} simulates.
 *
 * @param numNodes the number of nodes in the network, at most the size of the mainnet ping matrix (32)
 * @param seed the seed of the simulation; the same spec gives the same recording
 * @param events the number of events each recorded node releases from its orphan buffer, its own included; the
 * recording of a node ends with the step that releases the last of them
 * @param shape the shape of the events' transactions; the event creator picks the parents
 * @param signingSchema the schema the nodes sign their events with, or {@code null} for unsigned events
 * @param recordedNodes the number of recorded full nodes; the other nodes are mini-nodes
 * @param tick the time step of the simulation
 */
public record RecordingSpec(
        int numNodes,
        long seed,
        int events,
        @NonNull EventShape shape,
        @Nullable SigningSchema signingSchema,
        int recordedNodes,
        @NonNull Duration tick) {

    /**
     * Creates a spec and checks its values.
     *
     * @param numNodes the number of nodes in the network, at most the size of the mainnet ping matrix (32)
     * @param seed the seed of the simulation; the same spec gives the same recording
     * @param events the number of events each recorded node releases from its orphan buffer, its own included
     * @param shape the shape of the events' transactions; the event creator picks the parents
     * @param signingSchema the schema the nodes sign their events with, or {@code null} for unsigned events
     * @param recordedNodes the number of recorded full nodes; the other nodes are mini-nodes
     * @param tick the time step of the simulation
     * @throws IllegalArgumentException if a value is out of range
     */
    public RecordingSpec {
        requireNonNull(shape);
        requireNonNull(tick);
        if (recordedNodes < 1 || recordedNodes > numNodes) {
            throw new IllegalArgumentException(
                    "recordedNodes must be between 1 and numNodes (" + numNodes + "), but is " + recordedNodes);
        }
        if (numNodes > RealisticPingSamples.MAINNET.length) {
            throw new IllegalArgumentException("numNodes must be at most " + RealisticPingSamples.MAINNET.length
                    + ", the size of the mainnet ping matrix, but is " + numNodes);
        }
        if (events < 1) {
            throw new IllegalArgumentException("events must be positive, but is " + events);
        }
        if (tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("tick must be positive, but is " + tick);
        }
    }

    /**
     * Starts a spec with the values that have no default. Unless set otherwise, the events have the
     * {@link EventShape#MAINNET mainnet shape} and are unsigned, one node is recorded, and the tick is Falcon's
     * {@link FalconTestEnvironment#DEFAULT_GRANULARITY default}.
     *
     * @param numNodes the number of nodes in the network
     * @param seed the seed of the simulation
     * @param events the number of events each recorded node releases
     * @return the builder
     */
    @NonNull
    public static Builder builder(final int numNodes, final long seed, final int events) {
        return new Builder(numNodes, seed, events);
    }

    /**
     * Builds a {@link RecordingSpec}. Its values are checked when it is built.
     */
    public static final class Builder {

        private final int numNodes;
        private final long seed;
        private final int events;
        private EventShape shape = EventShape.MAINNET;

        @Nullable
        private SigningSchema signingSchema;

        private int recordedNodes = 1;
        private Duration tick = FalconTestEnvironment.DEFAULT_GRANULARITY;

        private Builder(final int numNodes, final long seed, final int events) {
            this.numNodes = numNodes;
            this.seed = seed;
            this.events = events;
        }

        /**
         * Sets the shape of the events' transactions.
         *
         * @param shape the shape; the event creator picks the parents
         * @return this builder
         */
        @NonNull
        public Builder shape(@NonNull final EventShape shape) {
            this.shape = requireNonNull(shape);
            return this;
        }

        /**
         * Makes the nodes sign their events.
         *
         * @param signingSchema the schema of the nodes' keys and signatures
         * @return this builder
         */
        @NonNull
        public Builder signingSchema(@NonNull final SigningSchema signingSchema) {
            this.signingSchema = requireNonNull(signingSchema);
            return this;
        }

        /**
         * Sets the number of recorded full nodes; the other nodes are mini-nodes.
         *
         * @param recordedNodes the number of recorded nodes
         * @return this builder
         */
        @NonNull
        public Builder recordedNodes(final int recordedNodes) {
            this.recordedNodes = recordedNodes;
            return this;
        }

        /**
         * Sets the time step of the simulation.
         *
         * @param tick the time step
         * @return this builder
         */
        @NonNull
        public Builder tick(@NonNull final Duration tick) {
            this.tick = requireNonNull(tick);
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the spec
         * @throws IllegalArgumentException if a value is out of range
         */
        @NonNull
        public RecordingSpec build() {
            return new RecordingSpec(numNodes, seed, events, shape, signingSchema, recordedNodes, tick);
        }
    }
}
