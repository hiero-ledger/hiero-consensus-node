// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Interface that provides access to the status progression of a node.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface SingleNodeStatusProgression {

    /**
     * Returns the node ID of the node which status progression has been recorded.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the list of platform status progression created during the test.
     *
     * @return the list of platform status
     */
    @NonNull
    List<PlatformStatus> statusProgression();
}
