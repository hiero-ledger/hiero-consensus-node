// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Interface for validating the results of a test.
 *
 * <p>This interface provides methods to assert various conditions related to logging,
 * event streams, and other behavior.
 */
public interface Validator {

    /**
     * Allows to configure the stdout validator that checks there are no Exceptions in the stdout.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator assertStdOut();

    /**
     * Allows to configure the eventStream validator that checks the event stream for unexpected entries.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator eventStream(@NonNull EventStreamConfig... configs);

    /**
     * Allows to configure the reconnect eventStream validator that checks the event stream of a node that
     * goes through one or more reconnects for unexpected entries.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator reconnectEventStream(@NonNull Node node);

    /**
     * This method is used to run all the validators configured in the given {@link Profile} that have
     * not been executed yet.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator validateRemaining(@NonNull Profile profile);

    /**
     * Allows to configure the consensus ratio validator that checks whether the ratio of transactions
     * that have been executed (and therefore reached consensus) is within the given range.
     *
     * @param configs configurations for the consensus ratio validator
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator consensusRatio(@NonNull RatioConfig... configs);

    /**
     * Allows to configure the stale ratio validator that checks whether the ratio of transactions
     * that have been reported as stale is within the given range.
     *
     * @param configs configurations for the stale ratio validator
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator staleRatio(@NonNull RatioConfig... configs);

    /**
     * Allows to configure the validator that checks whether the {@link org.hiero.consensus.model.status.PlatformStatus}
     * is going through the expected lifecycle.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator assertPlatformStatus(@NonNull PlatformStatusConfig... configs);

    /**
     * Allows to configure the validator that checks whether the metrics are within the expected range.
     *
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator assertMetrics(@NonNull MetricsConfig... configs);

    /**
     * Configuration for the event stream validator that checks the event stream for unexpected entries.
     */
    class EventStreamConfig {

        private static final Logger log = LogManager.getLogger(EventStreamConfig.class);

        /**
         * Creates a configuration to ignore the event streams of specific nodes.
         *
         * @param nodes the nodes to ignore
         * @return a {@code EventStreamConfig} instance
         */
        @NonNull
        public static EventStreamConfig ignoreNode(@NonNull final Node... nodes) {
            log.warn("Creating an event stream config is not implemented yet.");
            return new EventStreamConfig();
        }
    }

    /**
     * Configuration for the consensus ratio validator that checks whether the ratio of transactions
     * that have been executed (and therefore reached consensus) is within the given range.
     */
    class RatioConfig {

        private static final Logger log = LogManager.getLogger(RatioConfig.class);

        /**
         * Creates a configuration to check whether the ratio is within the given range.
         *
         * @param min the minimum ratio
         * @param max the maximum ratio
         * @return a {@code RatioConfig} instance
         */
        @NonNull
        public static RatioConfig within(final double min, final double max) {
            log.warn("Creating a ratio config is not implemented yet.");
            return new RatioConfig();
        }
    }

    /**
     * Configuration for the validator that checks whether the {@link org.hiero.consensus.model.status.PlatformStatus}
     * is going through the expected lifecycle.
     */
    class PlatformStatusConfig {}

    /**
     * Configuration for the validator that checks whether the metrics are within the expected range.
     */
    class MetricsConfig {}

    /**
     * A {@code Profile} represents a predefined set of validators with default configurations.
     */
    enum Profile {
        DEFAULT,
        HASHGRAPH
    }
}
