// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the log results of a group of nodes that were created during a test.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
@SuppressWarnings("unused")
public interface MultipleNodeLogResults extends OtterResult {

    /**
     * Returns the list of {@link SingleNodeLogResult} for all nodes
     *
     * @return the list of results
     */
    @NonNull
    List<SingleNodeLogResult> results();

    /**
     * Subscribes to log entries logged by the nodes.
     *
     * <p>The subscriber will be notified every time a new log entry is logged.
     *
     * @param subscriber the subscriber that will receive the log entries
     */
    void subscribe(@NonNull LogSubscriber subscriber);

    /**
     * Excludes the log results of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified node's results removed
     */
    @NonNull
    MultipleNodeLogResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the log results of a specific node from the current results.
     *
     * @param node the node whose log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified node's results removed
     */
    @NonNull
    default MultipleNodeLogResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Excludes the log results associated with the specified log marker from the current results.
     *
     * @param marker the {@link LogMarker} which associated log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified log marker's results removed
     */
    @NonNull
    MultipleNodeLogResults suppressingLogMarker(@NonNull LogMarker marker);
}
