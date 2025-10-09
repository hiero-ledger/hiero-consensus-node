// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface that provides access to the event stream results of a single node that are created during a test.
 */
public interface SingleNodeEventStreamResult {

    /**
     * Returns the node ID of the node that created the results.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the list of event stream files created during the test up to this moment. The list is ordered
     * according to the natural ordering of the paths.
     *
     * @return the list of event stream files
     */
    @NonNull
    List<Path> eventStreamFiles();

    /**
     * Returns the list of signature files created during the test up to this moment. The list is ordered
     * according to the natural ordering of the paths.
     *
     * @return the list of signature files
     */
    @NonNull
    List<Path> signatureFiles();

    /**
     * Marks the node as having reconnected during the test.
     *
     * @return a new instance of {@link SingleNodeEventStreamResult} with the node marked as reconnected
     */
    @NonNull
    SingleNodeEventStreamResult markReconnected();

    /**
     * Returns whether the node has reconnected during the test.
     *
     * @return {@code true} if the node has reconnected, {@code false} otherwise
     */
    boolean hasNotReconnected();
}
