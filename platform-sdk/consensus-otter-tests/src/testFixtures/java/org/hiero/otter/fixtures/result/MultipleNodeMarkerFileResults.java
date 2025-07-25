// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the results related to marker files.
 */
@SuppressWarnings("unused")
public interface MultipleNodeMarkerFileResults {

    /**
     * Returns the list of {@link SingleNodeMarkerFileResult} for all nodes
     *
     * @return the list of results for all nodes
     */
    @NonNull
    List<SingleNodeMarkerFileResult> results();

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified node's result excluded
     */
    @NonNull
    MultipleNodeMarkerFileResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param node the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified node's result excluded
     */
    @NonNull
    default MultipleNodeMarkerFileResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Subscribes to marker file changes the nodes go through.
     *
     * <p>The subscriber will be notified every time a node writes a new marker file.
     *
     * @param subscriber the subscriber that will receive the marker file updates
     */
    void subscribe(MarkerFileSubscriber subscriber);
}
