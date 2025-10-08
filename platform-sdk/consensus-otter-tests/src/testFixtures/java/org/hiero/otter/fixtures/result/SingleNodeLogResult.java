// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * Interface that provides access to the log results of a single node.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 * It allows retrieval of all log entries, the node ID, and the set of unique markers.
 */
@SuppressWarnings("unused")
public interface SingleNodeLogResult extends OtterResult {

    /**
     * Returns the ID of the node associated with this log result.
     *
     * @return the {@link NodeId} of the node
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the list of all log entries captured for this node.
     *
     * @return a list of {@link StructuredLog} entries
     */
    @NonNull
    List<StructuredLog> logs();

    /**
     * Excludes log entries associated with the specified {@link LogMarker} from the current log result.
     *
     * @param marker the {@link LogMarker} whose associated log entries are to be excluded
     * @return a new {@code SingleNodeLogResult} instance with the specified log marker's entries removed
     */
    @NonNull
    SingleNodeLogResult suppressingLogMarker(@NonNull LogMarker marker);

    /**
     * Excludes the log results from the specified logger class from the current results.
     *
     * @param clazz the class whose log results are to be excluded
     * @return a new {@code SingleNodeLogResult} instance with the specified log marker's results removed
     */
    @NonNull
    SingleNodeLogResult suppressingLoggerName(@NonNull final Class<?> clazz);

    /**
     * Excludes the log results from the specified logger name from the current results.
     *
     * @param loggerName the name of the logger whose log results are to be excluded
     * @return a new {@code SingleNodeLogResult} instance with the specified logger's results removed
     */
    @NonNull
    SingleNodeLogResult suppressingLoggerName(@NonNull String loggerName);

    /**
     * Returns the set of unique markers present in the log entries for this node.
     *
     * @return a set of {@link Marker} objects
     */
    @NonNull
    default Set<Marker> markers() {
        return logs().stream().map(StructuredLog::marker).collect(Collectors.toSet());
    }

    /**
     * Subscribes to {@link StructuredLog} entries logged by the node.
     *
     * <p>The subscriber will be notified every time a new log entry is created by the node.
     *
     * @param subscriber the subscriber that will receive the log entries
     */
    void subscribe(@NonNull LogSubscriber subscriber);
}
