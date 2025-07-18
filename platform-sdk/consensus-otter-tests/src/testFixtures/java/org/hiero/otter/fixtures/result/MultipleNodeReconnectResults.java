// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the results related to PCES files.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
@SuppressWarnings("unused")
public interface MultipleNodeReconnectResults {

    /**
     * Returns the list of {@link SingleNodePcesResult} for all nodes
     *
     * @return the list of results for all nodes
     */
    @NonNull
    List<SingleNodeReconnectResult> reconnectResults();

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeReconnectResults} with the specified node's result excluded
     */
    @NonNull
    MultipleNodeReconnectResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param node the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeReconnectResults} with the specified node's result excluded
     */
    @NonNull
    default MultipleNodeReconnectResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }
}
