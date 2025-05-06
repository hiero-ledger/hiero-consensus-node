// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.validator.LogLevelFilter;
import org.hiero.otter.fixtures.validator.LogMarkerFilter;
import org.hiero.otter.fixtures.validator.LogNodeFilter;

/**
 * Interface for validating the results of a test.
 *
 * <p>This interface provides methods to assert various conditions related to logging,
 * event streams, and other behavior.
 */
public interface Validator {

    /**
     * Allows to configure the log error validator that checks for error messages in the logs.
     * This will mostly be used to configure errors that are expected and can be ignored.
     *
     * @param configs configurations for the log error validator
     * @return this {@code Validator} instance for method chaining
     */
    @NonNull
    Validator assertLogs(@NonNull LogFilter... configs);

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
     * Configuration for the log error validator that checks for error messages in the logs.
     *
     * <p>This configuration can for example be used to specify errors that are expected and can be ignored.
     */
    interface LogFilter {
        /**
         * Specifies how the {@link LogMarkerFilter} interprets the marker set.
         */
        enum Mode {
            INCLUDE,
            EXCLUDE
        }

        /**
         * Determines whether a given log message should be filtered out.
         *
         * @param logMsg the structured log message to evaluate
         * @return {@code true} if the log message should be filtered out (ignored), {@code false} otherwise
         */
        boolean filter(@NonNull StructuredLog logMsg);

        /**
         * Creates a filter configuration that ignores log messages with any of the specified markers.
         *
         * @param markers the log markers to ignore
         * @return a {@code LogFilter} instance that filters out messages with the given markers
         */
        @NonNull
        static LogFilter ignoreMarkers(@NonNull final LogMarker... markers) {
            Objects.requireNonNull(markers, "markers cannot be null");
            final Set<Marker> markerSet =
                    Stream.of(markers).map(LogMarker::getMarker).collect(Collectors.toSet());
            return new LogMarkerFilter(Mode.EXCLUDE, markerSet);
        }

        /**
         * Creates a filter configuration that ignores log messages from the specified nodes.
         *
         * @param nodes the nodes to ignore
         * @return a {@code LogFilter} instance that filters out messages from the given nodes
         */
        @NonNull
        static LogFilter ignoreNodes(@NonNull final Node... nodes) {
            Objects.requireNonNull(nodes, "nodes cannot be null");
            final Set<Long> nodeSet =
                    Stream.of(nodes).map(Node::getSelfId).map(NodeId::id).collect(Collectors.toSet());
            return new LogNodeFilter(Mode.EXCLUDE, nodeSet);
        }

        /**
         * Creates a filter configuration that ignores log messages with a severity level less than or equal to the given level.
         *
         * @param level the maximum log level to ignore (e.g., {@code INFO} means messages with level {@code INFO} or lower are filtered out)
         * @return a {@code LogFilter} instance that filters out log messages up to and including the given level
         */
        @NonNull
        static LogFilter maxLogLevel(@NonNull final Level level) {
            Objects.requireNonNull(level, "level cannot be null");
            return new LogLevelFilter(level);
        }
    }

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
